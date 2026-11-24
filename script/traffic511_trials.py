"""Build stop-arrival scoring trials for reviewed freeway sections in a 511 regional GTFS feed."""

import argparse
import csv
import io
import itertools
import json
import math
import statistics
import sys
import zipfile
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from xml.etree import ElementTree
from zoneinfo import ZoneInfo

PACIFIC = "America/Los_Angeles"
FIELDS = [
    "capture",
    "agency_id",
    "route_id",
    "trip_start_date",
    "trip_id",
    "stop_sequence",
    "stop_id",
    "scheduled_arrival_time",
    "road_excess_seconds",
    "road_observed_at",
]


def epoch(value):
    parsed = datetime.fromisoformat(value)
    if parsed.utcoffset() is None:
        raise ValueError(f"Time requires a UTC offset: {value}")
    return int(parsed.timestamp())


def gtfs_epoch(day, value):
    # GTFS defines the service day origin as local noon minus 12 elapsed hours (also on DST days).
    noon = datetime.strptime(day, "%Y%m%d").replace(hour=12, tzinfo=ZoneInfo(PACIFIC))
    hours, minutes, seconds = map(int, value.split(":"))
    if hours < 0 or not 0 <= minutes < 60 or not 0 <= seconds < 60:
        raise ValueError(f"Invalid GTFS time: {value}")
    return int(noon.timestamp()) - 43200 + hours * 3600 + minutes * 60 + seconds


def table(gtfs, name):
    with gtfs.open(name) as binary:
        yield from csv.DictReader(
            io.TextIOWrapper(binary, encoding="utf-8-sig", newline="")
        )


def active_services(gtfs, day):
    weekday = datetime.strptime(day, "%Y%m%d").strftime("%A").lower()
    regular = set()
    if "calendar.txt" in gtfs.namelist():
        regular = {
            r["service_id"]
            for r in table(gtfs, "calendar.txt")
            if r["start_date"] <= day <= r["end_date"] and r[weekday] == "1"
        }
    if "calendar_dates.txt" in gtfs.namelist():
        for row in table(gtfs, "calendar_dates.txt"):
            if row["date"] == day:
                if row["exception_type"] == "1":
                    regular.add(row["service_id"])
                elif row["exception_type"] == "2":
                    regular.discard(row["service_id"])
    return regular


def detector_speeds(capture, stations):
    as_of = epoch((capture / "captured-at.txt").read_text().strip())
    with (capture / "speeds.txt").open(encoding="utf-8-sig", newline="") as stream:
        local = datetime.strptime(stream.readline().strip(), "%m/%d/%Y %H:%M:%S")
        offsets = [local.replace(tzinfo=ZoneInfo(PACIFIC), fold=i) for i in (0, 1)]
        valid = [
            dt
            for dt in offsets
            if 0 <= as_of - dt.timestamp() <= 1800
            and datetime.fromtimestamp(dt.timestamp(), dt.tzinfo).replace(tzinfo=None)
            == local
        ]
        if not valid:
            return as_of, None, {}
        observed = max(valid, key=lambda d: d.timestamp())
        speeds = {}
        for row in csv.reader(stream):
            if len(row) != 11:
                raise ValueError("Unexpected live PeMS station record format")
            if row[0] in stations and row[3] and row[9] and row[10]:
                speed, count, quality = map(float, (row[3], row[9], row[10]))
                if (
                    math.isfinite(speed)
                    and speed > 0
                    and math.isfinite(count)
                    and count > 0
                    and 50 <= quality <= 100
                ):
                    speeds[row[0]] = speed * 0.44704
    return as_of, observed, speeds


def typical_speeds(history, stations, observed, as_of):
    # Give each prior date one vote, independent of capture frequency.
    daily = defaultdict(lambda: defaultdict(list))
    if observed is None:
        return {}
    target = (observed.weekday() < 5, observed.hour, observed.minute // 30)
    for folder in history:
        received, time, speeds = detector_speeds(folder, stations)
        if (
            time is None
            or received >= as_of
            or not 0 < (observed.date() - time.date()).days <= 90
        ):
            continue
        if (time.weekday() < 5, time.hour, time.minute // 30) == target:
            for station, speed in speeds.items():
                daily[station][time.date()].append(speed)
    return {
        station: statistics.median(
            statistics.median(values) for values in days.values()
        )
        for station, days in daily.items()
        if len(days) >= 5
    }


def meters(coord, lon, lat):
    return (
        (coord[0] - lon) * 111320 * math.cos(math.radians(lat)),
        (coord[1] - lat) * 111320,
    )


def match_sections(gtfs, reviewed, capture):
    # The audited OSM geometry is ordered in travel direction and supplied independently of GTFS.
    from shapely import LineString, Point

    features = json.loads(reviewed.read_text(encoding="utf-8"))["features"]
    stations = {
        s.attrib["id"]: s.attrib
        for s in ElementTree.parse(capture / "stations.xml").findall(
            "./district[@id='4']/detector_stations/vds[@type='ML']"
        )
    }
    shape_ids = {f["properties"]["shape_id"] for f in features}
    shapes = defaultdict(list)
    for point in table(gtfs, "shapes.txt"):
        if point["shape_id"] in shape_ids and point.get("shape_dist_traveled"):
            shapes[point["shape_id"]].append(
                (
                    int(point["shape_pt_sequence"]),
                    (float(point["shape_pt_lon"]), float(point["shape_pt_lat"])),
                    float(point["shape_dist_traveled"]),
                )
            )
    matched = defaultdict(list)
    for feature in features:
        prop = feature["properties"]
        if (
            prop.get("shared_mainline") is not True
            or feature["geometry"]["type"] != "LineString"
        ):
            continue
        station = stations.get(prop["station_id"])
        if (
            not station
            or station["freeway_id"] != prop["freeway_id"]
            or station["freeway_dir"] != prop["freeway_dir"]
        ):
            continue
        begin, end = prop["from_shape_sequence"], prop["to_shape_sequence"]
        part = sorted(p for p in shapes[prop["shape_id"]] if begin <= p[0] <= end)
        if (
            len(part) < 2
            or part[0][0] != begin
            or part[-1][0] != end
            or part[0][2] >= part[-1][2]
            or any(p[2] > q[2] for p, q in itertools.pairwise(part))
        ):
            continue
        lon, lat = float(station["longitude"]), float(station["latitude"])

        shape = LineString([meters(p[1], lon, lat) for p in part])
        road = LineString(
            [meters(xy, lon, lat) for xy in feature["geometry"]["coordinates"]]
        )
        # Check alignment in travel order, continuity and bounded detector coverage.
        if (
            not (0 < road.length <= 1000 and shape.length > 0)
            or road.distance(Point(0, 0)) > 30
        ):
            continue
        samples = max(2, math.ceil(max(shape.length, road.length) / 10))
        if any(
            shape.interpolate(i / samples, normalized=True).distance(
                road.interpolate(i / samples, normalized=True)
            )
            > 20
            for i in range(samples + 1)
        ):
            continue
        key = (
            prop["route_id"],
            prop["shape_id"],
            prop["from_stop_id"],
            prop["to_stop_id"],
        )
        # Overlapping reviewed sections would charge the same delay twice.
        if any(a < part[-1][2] and b > part[0][2] for a, b, _, _ in matched[key]):
            raise ValueError(f"Overlapping reviewed road sections on {key}")
        matched[key].append((part[0][2], part[-1][2], station["id"], road.length))
    return matched


def build_trials(regional, reviewed, capture, history, day):
    with zipfile.ZipFile(regional) as gtfs:
        matched = match_sections(gtfs, reviewed, capture)
        sources = {
            station for regions in matched.values() for _, _, station, _ in regions
        }
        as_of, observed, speeds = detector_speeds(capture, sources)
        typical = typical_speeds(history, sources, observed, as_of)
        allowed = active_services(gtfs, day)
        routes = {
            r["route_id"]: r["agency_id"]
            for r in table(gtfs, "routes.txt")
            if r["route_type"] in ("3", "11")
            or r["route_type"].isdigit()
            and 700 <= int(r["route_type"]) <= 799
        }
        reviewed_pairs = {(route, shape) for route, shape, _, _ in matched}
        frequency = (
            {f["trip_id"] for f in table(gtfs, "frequencies.txt")}
            if "frequencies.txt" in gtfs.namelist()
            else set()
        )
        trips = {
            r["trip_id"]: (r["route_id"], r["shape_id"])
            for r in table(gtfs, "trips.txt")
            if r["service_id"] in allowed
            and r["route_id"] in routes
            and r["trip_id"] not in frequency
            and (r["route_id"], r["shape_id"]) in reviewed_pairs
        }
        stops = defaultdict(list)
        for row in table(gtfs, "stop_times.txt"):
            if row["trip_id"] in trips:
                stops[row["trip_id"]].append(row)
        for trip, rows in stops.items():
            route, shape = trips[trip]
            rows.sort(key=lambda s: int(s["stop_sequence"]))
            accumulated = 0.0
            for start, end in itertools.pairwise(rows):
                sections = matched.get(
                    (route, shape, start["stop_id"], end["stop_id"]), []
                )
                if (
                    not sections
                    or not start["departure_time"]
                    or not end["arrival_time"]
                ):
                    accumulated = 0.0
                    continue
                if not start.get("shape_dist_traveled") or not end.get(
                    "shape_dist_traveled"
                ):
                    accumulated = 0.0
                    continue
                at, to = (
                    gtfs_epoch(day, start["departure_time"]),
                    gtfs_epoch(day, end["arrival_time"]),
                )
                a, b = (
                    float(start["shape_dist_traveled"]),
                    float(end["shape_dist_traveled"]),
                )
                if not as_of <= at < to <= as_of + 1800 or any(
                    x < a or y > b for x, y, _, _ in sections
                ):
                    accumulated = 0.0
                    continue
                signal = ""
                if (
                    observed
                    and to <= observed.timestamp() + 1800
                    and all(
                        station in speeds and station in typical
                        for _, _, station, _ in sections
                    )
                ):
                    # Apply excess running time only. Scheduled dwell between consecutive hops is retained.
                    excess = sum(
                        meters * (1 / speeds[station] - 1 / typical[station])
                        for _, _, station, meters in sections
                    )
                    accumulated += max(0.0, excess)
                    signal = round(accumulated, 2)
                else:
                    accumulated = 0.0
                yield [
                    str(capture.resolve()),
                    routes[route],
                    route,
                    day,
                    trip,
                    end["stop_sequence"],
                    end["stop_id"],
                    end["arrival_time"],
                    signal,
                    observed.isoformat() if signal != "" else "",
                ]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("regional", type=Path, help="Matching daily regional GTFS")
    parser.add_argument(
        "sections", type=Path, help="Reviewed directed OSM sections as GeoJSON"
    )
    parser.add_argument("capture", type=Path)
    parser.add_argument("--service-date", required=True, help="GTFS YYYYMMDD")
    parser.add_argument(
        "--history",
        type=Path,
        nargs="*",
        default=[],
        help="Earlier timestamped PeMS capture directories",
    )
    args = parser.parse_args()
    writer = csv.writer(sys.stdout, lineterminator="\n")
    writer.writerow(FIELDS)
    writer.writerows(
        build_trials(
            args.regional, args.sections, args.capture, args.history, args.service_date
        )
    )


if __name__ == "__main__":
    main()
