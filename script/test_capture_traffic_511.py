"""Offline regression tests for the two kinds of 511 captures."""

import importlib.util
import io
import os
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

from google.transit import gtfs_realtime_pb2 as rt

spec = importlib.util.spec_from_file_location(
    "capture", Path(__file__).with_name("capture-traffic-511.py")
)
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)


def message(kind, issued=None):
    feed = rt.FeedMessage()
    feed.header.gtfs_realtime_version = "1.0"
    if issued is not None:
        feed.header.timestamp = issued
    entity = feed.entity.add(id="regional-entity")
    if kind == "trip_update":
        entity.trip_update.trip.trip_id = "AC:example"
    elif kind == "alert":
        entity.alert.informed_entity.add(agency_id="AC")
    else:
        entity.vehicle.vehicle.id = "example"
    return feed.SerializeToString()


class RegionalCaptureTest(unittest.TestCase):
    def test_regional_archive_and_three_realtime_feeds(self):
        token = "fixture-key"
        payload = io.BytesIO()
        with zipfile.ZipFile(payload, "w") as archive:
            archive.comment = token.encode()
            for name in ("agency.txt", "routes.txt", "trips.txt", "stop_times.txt"):
                entry = zipfile.ZipInfo(name)
                entry.comment = token.encode()
                archive.writestr(entry, name.encode())

        def download(url):
            endpoint = urlsplit(url)
            query = parse_qs(endpoint.query)
            self.assertEqual(query["api_key"], [token])
            if endpoint.path == "/transit/datafeeds":
                self.assertEqual(query["operator_id"], ["RG"])
                return payload.getvalue()
            self.assertEqual(query["agency"], ["RG"])
            kind = {
                "/transit/tripupdates": "trip_update",
                "/transit/servicealerts": "alert",
                "/transit/vehiclepositions": "vehicle",
            }[endpoint.path]
            # Historical snapshots retain the original provider timestamp.
            return message(kind, 100)

        with (
            tempfile.TemporaryDirectory() as work,
            patch.object(capture, "download", download),
        ):
            output = Path(work) / "regional"
            capture.capture(output, token, regional_transit=True)
            self.assertEqual(
                {p.name for p in output.iterdir()},
                {
                    "RG.gtfs.zip",
                    "tripupdates.pb",
                    "vehiclepositions.pb",
                    "alerts.pb",
                    "captured-at.txt",
                },
            )
            self.assertNotIn(token.encode(), (output / "RG.gtfs.zip").read_bytes())
            self.assertEqual(
                100,
                rt.FeedMessage.FromString(
                    (output / "tripupdates.pb").read_bytes()
                ).header.timestamp,
            )
            with zipfile.ZipFile(output / "RG.gtfs.zip") as archive:
                self.assertEqual(archive.read("routes.txt"), b"routes.txt")

    def test_failed_capture_leaves_destination_available_for_retry(self):
        with (
            tempfile.TemporaryDirectory() as work,
            patch.object(capture, "download", return_value=b"invalid"),
        ):
            output = Path(work) / "regional"
            with self.assertRaises(zipfile.BadZipFile):
                capture.capture(output, "fixture-key", regional_transit=True)
            self.assertFalse(output.exists())

    def test_malformed_realtime_is_not_published_in_a_capture(self):
        with (
            tempfile.TemporaryDirectory() as work,
            patch.object(capture, "capture_events", return_value=b"{}"),
            patch.object(capture, "download", return_value=b"<html>Maintenance</html>"),
        ):
            destination = Path(work) / "capture"
            with self.assertRaises(ValueError):
                capture.capture(destination, "fixture-key")
            self.assertFalse(destination.exists())


class LiveFilesTest(unittest.TestCase):
    def test_failed_trip_poll_keeps_other_streams_running_and_expires_previous_live_data(
        self,
    ):
        def download(url):
            if "/tripupdates?" in url:
                raise OSError("upstream error containing fixture-key")
            return message("alert" if "/servicealerts?" in url else "vehicle")

        with tempfile.TemporaryDirectory() as work:
            directory = Path(work)
            (directory / "tripupdates.pb").write_bytes(message("trip_update", 100))
            errors = io.StringIO()
            with (
                patch.object(capture, "download", download),
                patch.object(capture.sys, "stderr", errors),
                patch.object(capture.time, "sleep", side_effect=KeyboardInterrupt),
                self.assertRaises(KeyboardInterrupt),
            ):
                capture.refresh_transit(directory, "fixture-key")
            self.assertEqual(
                [],
                list(
                    capture.decode_realtime(
                        "tripupdates.pb", (directory / "tripupdates.pb").read_bytes()
                    ).entity
                ),
            )
            self.assertEqual(message("alert"), (directory / "alerts.pb").read_bytes())
            self.assertEqual(
                message("vehicle"), (directory / "vehiclepositions.pb").read_bytes()
            )
            self.assertNotIn("fixture-key", errors.getvalue())

    def test_capture_is_immutable(self):
        with tempfile.TemporaryDirectory() as work:
            directory = Path(work)
            (directory / "captured-at.txt").write_text("2026-11-24T00:00:00Z")
            for path in [directory, directory / "subdirectory"]:
                with self.assertRaisesRegex(ValueError, "timestamped"):
                    capture.LiveTransitFiles(path)
            self.assertEqual(["captured-at.txt"], [p.name for p in directory.iterdir()])

    def test_reject_malformed_and_wrong_stream_then_withdraw_and_recover(self):
        with tempfile.TemporaryDirectory() as work:
            directory = Path(work)
            files = capture.LiveTransitFiles(directory)
            key = "fixture-key"
            before = 10000
            last = message("trip_update", before)
            files.publish("tripupdates.pb", last, key, before)
            trips = directory / "tripupdates.pb"
            uninitialized = rt.FeedMessage()
            uninitialized.header.timestamp = before
            for value in (
                b"<html>error</html>",
                b"\x0a",
                uninitialized.SerializePartialToString(),
                message("vehicle", before),
                message("trip_update", before - 600),
                message("trip_update", before + 1000),
            ):
                with self.subTest(data=value), self.assertRaises(ValueError):
                    files.publish("tripupdates.pb", value, key, before + 10)
                self.assertEqual(last, trips.read_bytes())
            files.publish("alerts.pb", message("alert", before), key, before)
            files.expire(before + 1799)
            self.assertEqual(last, trips.read_bytes())
            files.expire(before + 1800)
            withdrawal = rt.FeedMessage.FromString(trips.read_bytes())
            self.assertTrue(withdrawal.IsInitialized())
            self.assertEqual(
                rt.FeedHeader.FULL_DATASET, withdrawal.header.incrementality
            )
            self.assertEqual(before + 1800, withdrawal.header.timestamp)
            self.assertEqual(0, len(withdrawal.entity))
            self.assertEqual(
                message("alert", before), (directory / "alerts.pb").read_bytes()
            )
            with self.assertRaises(ValueError):
                files.publish("tripupdates.pb", last, key, before + 1800)
            fresh = message("trip_update", before + 1801)
            files.publish("tripupdates.pb", fresh, key, before + 1802)
            self.assertEqual(fresh, trips.read_bytes())

    def test_restart_uses_source_or_receipt_and_never_creates_an_initial_withdrawal(
        self,
    ):
        with tempfile.TemporaryDirectory() as work:
            directory = Path(work)
            path = directory / "tripupdates.pb"
            files = capture.LiveTransitFiles(directory)
            files.expire(100000)
            self.assertFalse(path.exists())
            with self.assertRaises(ValueError):
                files.publish(
                    path.name, message("trip_update", 100), "fixture-key", 100000
                )
            self.assertFalse(path.exists())
            for issued, receipt in [(100, 1800), (None, 100)]:
                path.write_bytes(message("trip_update", issued))
                os.utime(path, (receipt, receipt))
                restarted = capture.LiveTransitFiles(directory)
                restarted.expire(1900)
                self.assertEqual(
                    0, len(rt.FeedMessage.FromString(path.read_bytes()).entity)
                )
                self.assertIsNone(capture.LiveTransitFiles(directory).trip_deadline)


if __name__ == "__main__":
    unittest.main()
