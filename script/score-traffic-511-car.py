"""Compare three predictions for a directed corridor against independent car crossing times."""

import argparse
import csv
import json
import math
import sys
from bisect import bisect_right
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from statistics import median
from urllib.parse import urlencode

VARIANTS = ("baseline_seconds", "closures_seconds", "traffic_seconds")
OBS_COLUMNS = ("observation_id", "corridor", "direction", "departed_at", "arrived_at")
PRED_COLUMNS = ("corridor", "direction", "departure", "capture", *VARIANTS)


def records(path, columns):
    with path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        missing = set(columns).difference(reader.fieldnames or ())
        if missing:
            raise ValueError(f"{path} missing columns {', '.join(sorted(missing))}")
        for line, row in enumerate(reader, 2):
            if None in row or None in row.values():
                raise ValueError(f"{path} line {line} has the wrong column count")
            yield row


def instant(value):
    result = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    if result.utcoffset() is None:
        raise ValueError(f"Timestamp needs an offset: {value}")
    return result.astimezone(timezone.utc)


def corridor_key(row):
    corridor, direction = row["corridor"].strip(), row["direction"].strip()
    if not corridor or not direction:
        raise ValueError("Each row needs a corridor and direction")
    return corridor, direction


def read_predictions(path):
    predictions = defaultdict(dict)
    captures = {}
    for row in records(path, PRED_COLUMNS):
        key = corridor_key(row)
        departure = instant(row["departure"])
        capture = row["capture"].strip()
        if not capture:
            raise ValueError(f"Missing capture for {key} at {departure}")
        if capture not in captures:
            stamp = path.parent / capture / "captured-at.txt"
            captures[capture] = instant(stamp.read_text(encoding="utf-8"))
        if captures[capture] > departure:
            raise ValueError(
                f"Capture after modeled departure for {key} at {departure}"
            )
        if departure in predictions[key]:
            raise ValueError(f"Duplicate prediction for {key} at {departure}")
        durations = {}
        for variant in VARIANTS:
            if value := row[variant].strip():
                seconds = float(value)
                if not math.isfinite(seconds) or seconds <= 0:
                    raise ValueError(f"Invalid {variant} for {key} at {departure}")
                durations[variant] = seconds
        predictions[key][departure] = durations
    return predictions


def error_metrics(values):
    if not values:
        return {"median": None, "p90": None}
    ordered = sorted(values)
    return {
        "median": round(median(ordered), 3),
        "p90": round(ordered[math.ceil(0.9 * len(ordered)) - 1], 3),
    }


def score(observations, predictions, tolerance_seconds=0):
    if tolerance_seconds < 0:
        raise ValueError("Maximum departure separation must be nonnegative")
    forecasts = read_predictions(predictions)
    departures = {key: sorted(by_time) for key, by_time in forecasts.items()}
    groups = {}
    seen = set()
    for row in records(observations, OBS_COLUMNS):
        key = corridor_key(row)
        observation = row["observation_id"].strip()
        if not observation or (key, observation) in seen:
            raise ValueError(
                f"Missing or duplicate observation ID for {key}: {observation}"
            )
        seen.add((key, observation))
        start = instant(row["departed_at"])
        elapsed = (instant(row["arrived_at"]) - start).total_seconds()
        if elapsed <= 0:
            raise ValueError(f"Nonpositive observed travel time for {observation}")
        if key not in groups:
            groups[key] = {
                "observations": 0,
                "matched_predictions": 0,
                "available": dict.fromkeys(VARIANTS, 0),
                "errors": {variant: [] for variant in VARIANTS},
            }
        group = groups[key]
        group["observations"] += 1
        times = departures.get(key, ())
        index = bisect_right(times, start) - 1
        if index < 0 or (start - times[index]).total_seconds() > tolerance_seconds:
            continue
        durations = forecasts[key][times[index]]
        group["matched_predictions"] += 1
        for variant in durations:
            group["available"][variant] += 1
        if len(durations) == len(VARIANTS):
            for variant, seconds in durations.items():
                group["errors"][variant].append(abs(seconds - elapsed))
    result = []
    for (corridor, direction), group in sorted(groups.items()):
        errors = group.pop("errors")
        n = len(errors[VARIANTS[0]])
        result.append(
            {
                "corridor": corridor,
                "direction": direction,
                **group,
                "paired": n,
                "paired_coverage": round(n / group["observations"], 6),
                "paired_absolute_error_seconds": {
                    variant: error_metrics(errors[variant]) for variant in VARIANTS
                },
            }
        )
    return result


def sample_url():
    """Fixed subset of FHWA's first Emeryville collection period, near the two gates (feet)."""
    query = {
        "$select": "vehicle_id,frame_id,global_time,local_y,lane_id",
        "$where": "location='i-80' AND v_class=2 AND vehicle_id BETWEEN 30 AND 100 "
        "AND global_time BETWEEN 1113433135000 AND 1113434050000 "
        "AND ((local_y BETWEEN 85 AND 115) OR (local_y BETWEEN 1385 AND 1415))",
        "$order": "vehicle_id,global_time",
        "$limit": 50000,
    }
    return "https://data.transportation.gov/resource/8ect-6jqj.csv?" + urlencode(query)


def crossing(previous, current, gate):
    f0, t0, y0, lane0 = previous
    f1, t1, y1, lane1 = current
    if (
        f1 == f0 + 1
        and t1 - t0 == 100
        and y0 < gate <= y1
        and 1 <= lane0 <= 6
        and 1 <= lane1 <= 6
    ):
        return t0 + round((gate - y0) / (y1 - y0) * (t1 - t0))
    return None


def ngsim_observations(path):
    columns = ("vehicle_id", "frame_id", "global_time", "local_y", "lane_id")
    tracks = defaultdict(list)
    for row in records(path, columns):
        time = int(row["global_time"])
        if not 1113433135000 <= time <= 1113434050000:
            raise ValueError(
                "Use the sample's single collection period; vehicle IDs can be reused"
            )
        tracks[int(row["vehicle_id"])].append(
            (int(row["frame_id"]), time, float(row["local_y"]), int(row["lane_id"]))
        )
    for vehicle, frames in sorted(tracks.items()):
        frames.sort()
        times = {}
        for previous, current in zip(frames, frames[1:]):
            for gate in (100, 1400):
                t = crossing(previous, current, gate)
                if t is not None and gate not in times:
                    times[gate] = t
        if len(times) == 2 and times[1400] > times[100]:
            stamps = [
                datetime.fromtimestamp(times[g] / 1000, timezone.utc).isoformat(
                    timespec="milliseconds"
                )
                for g in (100, 1400)
            ]
            yield (
                f"ngsim-20050413-{vehicle}",
                "I-80 Emeryville NGSIM 100–1400 ft",
                "EB",
                *stamps,
            )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser(
        "sample-url", help="Print the public USDOT historical sample URL"
    )
    sample = commands.add_parser(
        "ngsim", help="Convert the sample frames into measured journeys"
    )
    sample.add_argument("frames", type=Path)
    sample.add_argument("observations", type=Path)
    compare = commands.add_parser(
        "score", help="Summarize paired held-out errors and coverage"
    )
    compare.add_argument("observations", type=Path)
    compare.add_argument("predictions", type=Path)
    compare.add_argument("--max-separation-seconds", type=int, default=0)
    args = parser.parse_args()
    try:
        if args.command == "sample-url":
            print(sample_url())
        elif args.command == "ngsim":
            journeys = list(ngsim_observations(args.frames))
            with args.observations.open("x", newline="", encoding="utf-8") as output:
                writer = csv.writer(output)
                writer.writerow(OBS_COLUMNS)
                writer.writerows(journeys)
            print(f"Wrote {len(journeys)} measured journeys", file=sys.stderr)
        else:
            print(
                json.dumps(
                    score(
                        args.observations, args.predictions, args.max_separation_seconds
                    ),
                    indent=2,
                )
            )
    except (OSError, ValueError) as exc:
        parser.exit(1, f"{exc}\n")


if __name__ == "__main__":
    main()
