"""Capture a complete Bay Area traffic snapshot and the companion regional GTFS-RT feeds."""

import argparse
import gzip
import io
import json
import os
import sys
import tempfile
import time
import zipfile
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from datetime import datetime, timezone
from http.client import HTTPException
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urljoin, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

from google.protobuf.message import DecodeError
from google.transit import gtfs_realtime_pb2 as gtfs_rt

REALTIME_ENTITIES = {
    "tripupdates.pb": "trip_update",
    "vehiclepositions.pb": "vehicle",
    "alerts.pb": "alert",
}
TRIP_MAX_AGE = 30 * 60


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def download(url):
    request = Request(url, headers={"Accept-Encoding": "gzip"})
    try:
        with build_opener(NoRedirect).open(request, timeout=45) as response:
            data = response.read()
            if response.headers.get("Content-Encoding", "").lower() == "gzip":
                return gzip.decompress(data)
            return data
    except HTTPError as error:
        raise ValueError(f"{urlsplit(url).path} returned HTTP {error.code}") from None
    except URLError:
        raise ValueError(f"Could not reach {urlsplit(url).hostname}") from None


def capture_events(key):
    endpoint = "https://api.511.org/traffic/events"
    events = []
    offset = 0
    for _ in range(12):
        query = urlencode(dict(api_key=key, status="ACTIVE", limit=500, offset=offset))
        page = json.loads(download(f"{endpoint}?{query}"))
        if not isinstance(page.get("events"), list):
            raise ValueError("Invalid 511 Events page")
        events.extend(page["events"])
        next_link = (page.get("pagination") or {}).get("next_url")
        if not next_link:
            payload = json.dumps({"events": events}, separators=(",", ":")).encode()
            if key.encode() in payload:
                raise ValueError("Credential found in Events response")
            return payload
        next_page = urlsplit(urljoin(endpoint, next_link))
        if (
            next_page.hostname != "api.511.org"
            or next_page.path.lower().rstrip("/") != "/traffic/events"
        ):
            raise ValueError("Unexpected Events pagination link")
        offsets = parse_qs(next_page.query).get("offset", [])
        if len(offsets) != 1 or not offsets[0].isdigit() or int(offsets[0]) <= offset:
            raise ValueError("Invalid Events pagination offset")
        offset = int(offsets[0])
    raise ValueError("Events capture exceeded its page budget")


def transit_urls(key):
    query = urlencode(dict(api_key=key, agency="RG"))
    return {
        "tripupdates.pb": f"https://api.511.org/transit/tripupdates?{query}",
        "vehiclepositions.pb": f"https://api.511.org/transit/vehiclepositions?{query}",
        "alerts.pb": f"https://api.511.org/transit/servicealerts?{query}",
    }


def decode_realtime(name, data):
    try:
        feed = gtfs_rt.FeedMessage.FromString(data)
    except DecodeError:
        raise ValueError(f"Invalid regional GTFS-RT protobuf in {name}") from None
    if (
        not feed.IsInitialized()
        or not feed.header.gtfs_realtime_version.strip()
        or feed.header.incrementality != gtfs_rt.FeedHeader.FULL_DATASET
        or any(
            e.is_deleted or not e.HasField(REALTIME_ENTITIES[name]) for e in feed.entity
        )
    ):
        raise ValueError(f"Expected a complete regional GTFS-RT dataset in {name}")
    return feed


def published_at(feed, received):
    return feed.header.timestamp if feed.header.HasField("timestamp") else received


class LiveTransitFiles:
    """Single writer for live files, with an expiry deadline derived from the published trips."""

    def __init__(self, directory):
        self.directory = directory.resolve()
        if any(
            (p / "captured-at.txt").exists()
            for p in (self.directory, *self.directory.parents)
        ):
            raise ValueError("Choose a live directory outside timestamped captures")
        self.directory.mkdir(parents=True, exist_ok=True)
        self.trip_deadline = None
        trips = self.directory / "tripupdates.pb"
        if trips.exists():
            try:
                feed = decode_realtime(trips.name, trips.read_bytes())
                if feed.entity:
                    received = trips.stat().st_mtime
                    self.trip_deadline = (
                        min(published_at(feed, received), received) + TRIP_MAX_AGE
                    )
            except (ValueError, OSError):
                print(
                    "Existing live TripUpdates could not be validated", file=sys.stderr
                )

    def write(self, name, data):
        with tempfile.TemporaryDirectory(dir=self.directory) as staging:
            path = Path(staging) / name
            path.write_bytes(data)
            path.replace(self.directory / name)

    def publish(self, name, data, key, now):
        if key.encode() in data:
            raise ValueError(f"Credential found in regional feed {name}")
        feed = decode_realtime(name, data)
        if name == "tripupdates.pb":
            issued = published_at(feed, now)
            if not -60 <= now - issued < TRIP_MAX_AGE:
                raise ValueError("Stale or future RG TripUpdates")
            deadline = min(issued, now) + TRIP_MAX_AGE
            if self.trip_deadline is not None and deadline < self.trip_deadline:
                raise ValueError("Out-of-order RG TripUpdates")
            self.write(name, data)
            self.trip_deadline = deadline if feed.entity else None
        else:
            self.write(name, data)

    def expire(self, now):
        if self.trip_deadline is not None and now >= self.trip_deadline:
            empty = gtfs_rt.FeedMessage()
            empty.header.gtfs_realtime_version = "2.0"
            empty.header.incrementality = gtfs_rt.FeedHeader.FULL_DATASET
            empty.header.timestamp = int(now)
            self.write("tripupdates.pb", empty.SerializeToString())
            self.trip_deadline = None


def refresh_transit(directory, key):
    """Publish regional GTFS-RT for OTP, even if a sibling endpoint is unavailable."""
    files = LiveTransitFiles(directory)
    urls = transit_urls(key)
    intervals = {"tripupdates.pb": 180, "vehiclepositions.pb": 600, "alerts.pb": 600}
    next_poll = dict.fromkeys(urls, 0)
    pending = {}
    with ThreadPoolExecutor(max_workers=len(urls)) as downloads:
        while True:
            # The publishing thread can withdraw expired predictions while downloads are blocked.
            try:
                files.expire(time.time())
            except OSError:
                print(
                    "Could not withdraw expired live TripUpdates",
                    file=sys.stderr,
                    flush=True,
                )
                files.trip_deadline = time.time() + 1
            for name, url in urls.items():
                if name not in pending and time.monotonic() >= next_poll[name]:
                    pending[name] = downloads.submit(download, url)
            delays = [next_poll[n] - time.monotonic() for n in urls if n not in pending]
            if files.trip_deadline is not None:
                delays.append(files.trip_deadline - time.time())
            timeout = max(0, min(delays)) if delays else None
            if not pending:
                time.sleep(timeout)
                continue
            ready, _ = wait(pending.values(), timeout, return_when=FIRST_COMPLETED)
            for name, result in list(pending.items()):
                if result in ready:
                    try:
                        files.publish(name, result.result(), key, time.time())
                    except (ValueError, OSError, HTTPException, EOFError):
                        print(
                            f"Could not refresh live {name}",
                            file=sys.stderr,
                            flush=True,
                        )
                    next_poll[name] = time.monotonic() + intervals[name]
                    del pending[name]


def write_regional_gtfs(path, payload, key):
    # The published ZIP can retain the credential-bearing request URL in its archive metadata.
    # Copy only the entry names and contents needed by GTFS readers.
    with (
        zipfile.ZipFile(io.BytesIO(payload)) as source,
        zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as output,
    ):
        if not {"agency.txt", "routes.txt", "trips.txt", "stop_times.txt"} <= set(
            source.namelist()
        ):
            raise ValueError("The regional GTFS archive is missing required tables")
        for entry in source.infolist():
            if key in entry.filename:
                raise ValueError("Credential found in regional GTFS entry name")
            with (
                source.open(entry) as reader,
                output.open(entry.filename, "w") as writer,
            ):
                token = key.encode()
                tail = b""
                while chunk := reader.read(1024 * 1024):
                    if token in tail + chunk:
                        raise ValueError("Credential found in regional GTFS contents")
                    tail = chunk[-len(token) :]
                    writer.write(chunk)


def capture(directory, key, regional_transit=False):
    if directory.exists():
        raise ValueError(f"Choose a new destination directory: {directory}")
    directory.parent.mkdir(parents=True, exist_ok=True)
    # Publish the directory only after every dataset is complete.
    with tempfile.TemporaryDirectory(dir=directory.parent) as staging:
        output = Path(staging) / "capture"
        output.mkdir()
        urls = transit_urls(key)
        if regional_transit:
            query = urlencode(dict(api_key=key, operator_id="RG"))
            urls = {
                "RG.gtfs.zip": f"https://api.511.org/transit/datafeeds?{query}",
                **urls,
            }
        else:
            (output / "events.json").write_bytes(capture_events(key))
            urls.update(
                {
                    "work-zones.json": "https://api.511.org/traffic/wzdx?"
                    + urlencode(
                        dict(
                            api_key=key,
                            includeAllDefinedEnums="true",
                            allActiveAndFutureEvents="true",
                        )
                    ),
                    "stations.xml": "https://pems.dot.ca.gov/feeds/vds_config.xml",
                    "speeds.txt": "https://pems.dot.ca.gov/feeds/D4/Data/5min/5minagg_latest.txt",
                }
            )
        for name, url in urls.items():
            payload = download(url)
            if name == "RG.gtfs.zip":
                write_regional_gtfs(output / name, payload, key)
                continue
            if not payload or key.encode() in payload:
                raise ValueError(f"Invalid response for {name}")
            if name in REALTIME_ENTITIES:
                decode_realtime(name, payload)
            (output / name).write_bytes(payload)
        captured = datetime.now(timezone.utc).isoformat(timespec="seconds")
        (output / "captured-at.txt").write_text(captured + "\n", encoding="ascii")
        output.rename(directory)
    print(f"Captured {captured} to {directory}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--regional-transit",
        action="store_true",
        help="capture the regional RG static GTFS and all three RG realtime feeds",
    )
    mode.add_argument(
        "--refresh-transit",
        action="store_true",
        help="continually refresh the three regional GTFS-RT files for a live router",
    )
    args = parser.parse_args()
    key = os.environ.get("511_API_KEY")
    if not key:
        parser.exit(1, "Set 511_API_KEY in the environment.\n")
    try:
        if args.refresh_transit:
            refresh_transit(args.directory, key)
        else:
            capture(args.directory, key, args.regional_transit)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        sys.exit(str(error))


if __name__ == "__main__":
    main()
