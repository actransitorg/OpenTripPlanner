"""Exercise the offline scorer with two synthetic captures and matching monthly stop observations."""

import csv
import json
import runpy
import tempfile
import unittest
import zipfile
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path

from google.transit import gtfs_realtime_pb2
from traffic511_trials import build_trials

scorer = runpy.run_path(str(Path(__file__).with_name("score-traffic-511-transit.py")))


class ReplayTest(unittest.TestCase):
    def test_measured_sections_accumulate_run_delay_and_preserve_dwell(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            regional = directory / "RG.zip"
            with zipfile.ZipFile(regional, "w") as gtfs:
                gtfs.writestr(
                    "routes.txt", "route_id,agency_id,route_type\nAC:TEST,AC,3\n"
                )
                gtfs.writestr(
                    "trips.txt",
                    "trip_id,service_id,route_id,shape_id\nAC:T,weekday,AC:TEST,S\n",
                )
                gtfs.writestr(
                    "calendar_dates.txt",
                    "service_id,date,exception_type\nweekday,20260713,1\n",
                )
                gtfs.writestr(
                    "stop_times.txt",
                    "trip_id,stop_id,stop_sequence,arrival_time,departure_time,shape_dist_traveled\n"
                    "AC:T,A,1,08:35:00,08:36:00,0\nAC:T,B,2,08:40:00,08:42:00,250\n"
                    "AC:T,C,3,08:45:00,08:47:00,500\n",
                )
                gtfs.writestr(
                    "shapes.txt",
                    "shape_id,shape_pt_sequence,shape_pt_lon,shape_pt_lat,shape_dist_traveled\n"
                    "S,1,-122.400,37.8,0\nS,2,-122.397,37.8,250\nS,3,-122.394,37.8,500\n",
                )
            history = []
            for date in (6, 7, 8, 9, 10, 13):
                folder = directory / str(date)
                folder.mkdir()
                (folder / "captured-at.txt").write_text(
                    f"2026-07-{date:02d}T08:32:00-07:00"
                )
                speed = 25 if date == 13 else 50
                (folder / "speeds.txt").write_text(
                    f"07/{date:02d}/2026 08:25:00\n123,0,0,{speed},0,0,0,0,0,10,100\n"
                )
                if date != 13:
                    history.append(folder)
            capture = directory / "13"
            (capture / "stations.xml").write_text(
                '<pems><district id="4"><detector_stations>'
                '<vds type="ML" id="123" latitude="37.8" longitude="-122.397" freeway_id="80" freeway_dir="E"/>'
                "</detector_stations></district></pems>"
            )
            sections = directory / "reviewed.json"
            points = [[-122.400, 37.8], [-122.397, 37.8], [-122.394, 37.8]]
            features = []
            for i, stops in enumerate([("A", "B"), ("B", "C")]):
                features.append(
                    {
                        "type": "Feature",
                        "properties": {
                            "shared_mainline": True,
                            "station_id": "123",
                            "freeway_id": "80",
                            "freeway_dir": "E",
                            "route_id": "AC:TEST",
                            "shape_id": "S",
                            "from_shape_sequence": i + 1,
                            "to_shape_sequence": i + 2,
                            "from_stop_id": stops[0],
                            "to_stop_id": stops[1],
                        },
                        "geometry": {
                            "type": "LineString",
                            "coordinates": points[i : i + 2],
                        },
                    }
                )

            def run(folders):
                sections.write_text(
                    json.dumps({"type": "FeatureCollection", "features": features})
                )
                return list(
                    build_trials(regional, sections, capture, folders, "20260713")
                )

            first, second = run(history)
            self.assertEqual(("B", "08:40:00"), tuple(first[6:8]))
            self.assertEqual(("C", "08:45:00"), tuple(second[6:8]))
            self.assertAlmostEqual(11.8, first[8], delta=0.1)
            self.assertAlmostEqual(2 * first[8], second[8], delta=0.02)
            self.assertEqual("2026-07-13T08:25:00-07:00", second[9])
            self.assertEqual(["", ""], [row[8] for row in run(history[:4])])
            features[1]["geometry"]["coordinates"].reverse()
            self.assertEqual(["B"], [row[6] for row in run(history)])
            features[0]["properties"]["freeway_dir"] = "W"
            self.assertEqual([], run(history))

    def test_holdout_preserves_agency_predictions_and_partial_updates(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            rows = []
            observations = [
                (
                    "trip_id,trip_start_date,stop_id,stop_sequence,schedule_relationship,"
                    "scheduled_arrival_time,observed_arrival_time,uncertainty\n"
                )
            ]
            for day, trips in (
                ("20260701", ["train"]),
                ("20260702", ["unpredicted", "agency", "partial", "skipped"]),
            ):
                capture = directory / day
                capture.mkdir()
                scheduled = scorer["gtfs_epoch"](day, "24:20:00")
                as_of = scheduled - 400
                iso = datetime.fromtimestamp(as_of, UTC).isoformat()
                (capture / "captured-at.txt").write_text(iso)
                feed = gtfs_realtime_pb2.FeedMessage()
                feed.header.gtfs_realtime_version = "2.0"
                feed.header.timestamp = as_of
                for trip in trips:
                    if trip in ("agency", "partial", "skipped"):
                        update = feed.entity.add(id=trip).trip_update
                        update.trip.trip_id = "AC:" + trip
                        update.trip.start_date = day
                        if trip in ("agency", "skipped"):
                            stop = update.stop_time_update.add(
                                stop_sequence=3, stop_id="AC:1"
                            )
                            if trip == "agency":
                                stop.arrival.time = scheduled + 95
                            else:
                                stop.schedule_relationship = stop.SKIPPED
                    rows.append(
                        [
                            day,
                            "AC",
                            "AC:1A",
                            day,
                            "AC:" + trip,
                            3,
                            "AC:1",
                            "24:20:00",
                            120,
                            iso,
                        ]
                    )
                    observations.append(
                        f"AC:{trip}:123abc,{day},AC:1,3,0,24:20:00,24:21:30,30\n"
                    )
                (capture / "tripupdates.pb").write_bytes(feed.SerializeToString())
            trials_path = directory / "trials.csv"
            with trials_path.open("w", newline="") as stream:
                writer = csv.writer(stream)
                writer.writerow(
                    [
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
                )
                writer.writerows(rows)
            archive = directory / "month.zip"
            with zipfile.ZipFile(archive, "w") as shape:
                shape.writestr("stop_observations.txt", "".join(observations))
            rejected = Counter()
            trials = scorer["read_trials"](trials_path, rejected)
            actuals = scorer["outcomes"](archive, trials)
            report = scorer["score"](trials, actuals, "20260702", 1, 120, rejected)
            self.assertEqual(
                1, report["excluded"]["removed or non-scheduled trip/stop"]
            )
            (group,) = report["groups"]
            self.assertEqual(
                ("5-15 min", 0.75, 1),
                (
                    group["lead"],
                    group["road_delay_factor"],
                    group["exposed_holdout_trips"],
                ),
            )
            self.assertEqual(
                0, group["errors"]["exposed_candidate"]["median_absolute_error_s"]
            )
            self.assertEqual(
                5, group["errors"]["agency_prediction"]["median_absolute_error_s"]
            )
            self.assertEqual(3, group["errors"]["agency_plus_road_estimate"]["n"])
            self.assertEqual(
                5,
                group["errors"]["agency_plus_road_estimate"]["median_absolute_error_s"],
            )
            self.assertEqual(
                90, group["errors"]["agency_plus_road_estimate"]["p90_absolute_error_s"]
            )
            sparse = scorer["score"](trials, actuals, "20260702", 30, 120, rejected)[
                "groups"
            ][0]
            self.assertIsNone(sparse["road_delay_factor"])
            self.assertEqual(
                sparse["errors"]["agency_plus_schedule"],
                sparse["errors"]["agency_plus_road_estimate"],
            )


if __name__ == "__main__":
    unittest.main()
