import csv
import importlib.util
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "car_score", Path(__file__).with_name("score-traffic-511-car.py")
)
SCORER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCORER)


class CarScoreTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        (self.root / "capture").mkdir()
        (self.root / "capture" / "captured-at.txt").write_text("2026-04-01T11:00:00Z")

    def csv_file(self, name, columns, rows):
        path = self.root / name
        with path.open("w", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(columns)
            writer.writerows(rows)
        return path

    def test_pairs_identical_trips_and_reports_missing_predictions(self):
        def t(sec):
            return f"2026-04-01T11:{sec // 60:02d}:{sec % 60:02d}Z"

        obs = self.csv_file(
            "obs.csv",
            SCORER.OBS_COLUMNS,
            [
                ("1", "I80", "EB", t(10), t(110)),
                ("2", "I80", "EB", t(18), t(138)),
                ("3", "I80", "EB", t(40), t(140)),
                ("4", "I80", "EB", t(59), t(159)),
                ("5", "I80", "WB", t(10), t(110)),
            ],
        )
        pred = self.csv_file(
            "pred.csv",
            SCORER.PRED_COLUMNS,
            [
                ("I80", "EB", t(40), "capture", 70, 80, ""),
                ("I80", "EB", t(10), "capture", 60, 100, 110),
                ("I80", "EB", t(65), "capture", 100, 100, 100),
            ],
        )
        east, west = SCORER.score(obs, pred, 10)
        self.assertEqual(4, east["observations"])
        self.assertEqual(3, east["matched_predictions"])
        self.assertEqual(2, east["paired"])
        self.assertEqual(0.5, east["paired_coverage"])
        self.assertEqual(
            {"median": 50, "p90": 60},
            east["paired_absolute_error_seconds"]["baseline_seconds"],
        )
        self.assertEqual(
            {"median": 10, "p90": 10},
            east["paired_absolute_error_seconds"]["traffic_seconds"],
        )
        self.assertEqual(3, east["available"]["closures_seconds"])
        self.assertEqual(2, east["available"]["traffic_seconds"])
        self.assertEqual(0, west["paired"])
        self.assertIsNone(
            west["paired_absolute_error_seconds"]["baseline_seconds"]["median"]
        )

    def test_rejects_future_capture_and_ambiguous_or_invalid_predictions(self):
        row = ["I80", "EB", "2026-04-01T11:00:00Z", "capture", 50, 50, 55]
        for rows in [
            [[*row[:2], "2026-04-01T10:59:59Z", *row[3:]]],
            [[*row[:2], "2026-04-01T11:00:00", *row[3:]]],
            [[*row[:-1], "nan"]],
            [row, row],
        ]:
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                SCORER.read_predictions(
                    self.csv_file("pred.csv", SCORER.PRED_COLUMNS, rows)
                )

    def test_crossing_times_require_consecutive_mainline_frames_at_both_gates(self):
        columns = ("vehicle_id", "frame_id", "global_time", "local_y", "lane_id")
        start = 1113433200000
        rows = [
            (41, 1, start, 99, 2),
            (41, 2, start + 100, 101, 2),
            (41, 401, start + 40000, 1399, 3),
            (41, 402, start + 40100, 1403, 3),
        ]
        # A vehicle leaving before the second gate and one missing a frame at entry.
        rows += [
            (42, 1, start, 99, 2),
            (42, 2, start + 100, 101, 2),
            (43, 1, start, 99, 3),
            (43, 3, start + 200, 101, 3),
            (43, 401, start + 40000, 1399, 3),
            (43, 402, start + 40100, 1403, 3),
        ]
        # The exit is on a ramp.
        rows += [
            (44, frame, time, y, 7 if y > 1000 else 3)
            for _, frame, time, y, _ in rows[:4]
        ]
        observations = list(
            SCORER.ngsim_observations(
                self.csv_file("frames.csv", columns, reversed(rows))
            )
        )
        self.assertEqual(1, len(observations))
        name, _, direction, departure, arrival = observations[0]
        self.assertEqual("ngsim-20050413-41", name)
        self.assertEqual("EB", direction)
        self.assertAlmostEqual(
            39.975,
            (SCORER.instant(arrival) - SCORER.instant(departure)).total_seconds(),
        )


if __name__ == "__main__":
    unittest.main()
