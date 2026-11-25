"""Score road-based stop arrival estimates offline against the monthly regional GTFS observations.

Each trial describes one stop on a fixed-time trip at the instant a capture completed. Supply
capture,agency_id,route_id,trip_start_date,trip_id,stop_sequence,stop_id,scheduled_arrival_time,
road_excess_seconds,road_observed_at. The last two columns may be empty. Populate them only for
verified shared-road hops, using excess travel time over typical running time from inputs available
at capture. Get the scheduled arrival from the daily GTFS used to produce the capture's trip plan.
capture is a directory (relative to the trials CSV) from the Traffic511Capture Java command.
"""

import argparse
import csv
import json
import math
import re
import statistics
import zipfile
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from google.transit import gtfs_realtime_pb2
from traffic511_trials import epoch, gtfs_epoch, table


def bucket(lead):
    if lead < 300:
        return "0-5 min"
    if lead < 900:
        return "5-15 min"
    if lead < 1800:
        return "15-30 min"
    return "30+ min"


@dataclass(frozen=True)
class Trial:
    day: str
    trip: str
    sequence: int
    stop: str
    agency: str
    route: str
    as_of: int
    scheduled: int
    road_excess: float | None
    agency_time: int | None
    has_update: bool

    @property
    def key(self):
        return self.day, self.trip, self.sequence, self.stop

    @property
    def group(self):
        return self.agency, self.route, bucket(self.scheduled - self.as_of)


def capture_predictions(directory):
    as_of = epoch((directory / "captured-at.txt").read_text().strip())
    feed = gtfs_realtime_pb2.FeedMessage.FromString(
        (directory / "tripupdates.pb").read_bytes()
    )
    if (
        feed.header.incrementality != feed.header.FULL_DATASET
        or not 0 <= as_of - feed.header.timestamp <= 1800
    ):
        raise ValueError(
            f"Full contemporaneous TripUpdates are required for {directory}"
        )
    updates = defaultdict(list)
    for entity in feed.entity:
        if entity.HasField("trip_update") and not entity.is_deleted:
            update = entity.trip_update
            updates[update.trip.trip_id].append(update)
    return as_of, updates


def agency_prediction(updates, row, as_of, scheduled):
    applicable = [
        u for u in updates if u.trip.start_date in ("", row["trip_start_date"])
    ]
    if not applicable:
        return False, None, False
    # Date-less or ambiguous trip updates keep the entire instance out of the inference cohort.
    exact = [u for u in applicable if u.trip.start_date == row["trip_start_date"]]
    if len(applicable) != 1 or len(exact) != 1:
        return True, None, False
    update = exact[0]
    if update.trip.schedule_relationship != update.trip.SCHEDULED:
        return True, None, True
    if update.HasField("timestamp") and not 0 <= as_of - update.timestamp <= 1800:
        return True, None, False
    for stop in update.stop_time_update:
        if stop.stop_sequence != int(row["stop_sequence"]) or stop.stop_id not in (
            "",
            row["stop_id"],
        ):
            continue
        if stop.schedule_relationship == stop.SKIPPED:
            return True, None, True
        if stop.schedule_relationship != stop.SCHEDULED:
            break
        if stop.arrival.HasField("time"):
            return True, stop.arrival.time, False
        if stop.arrival.HasField("delay"):
            return True, scheduled + stop.arrival.delay, False
    return True, None, False


def read_trials(path, skipped):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        by_capture = defaultdict(list)
        for row in csv.DictReader(stream):
            by_capture[path.parent / row["capture"]].append(row)
    trials = []
    seen = set()
    for directory, rows in by_capture.items():
        as_of, updates = capture_predictions(directory)
        for row in rows:
            day, trip = row["trip_start_date"], row["trip_id"]
            scheduled = gtfs_epoch(day, row["scheduled_arrival_time"])
            has_update, agency_time, excluded = agency_prediction(
                updates[trip], row, as_of, scheduled
            )
            if excluded:
                skipped["removed or non-scheduled trip/stop"] += 1
                continue
            if scheduled <= as_of:
                skipped["scheduled arrival already passed"] += 1
                continue
            excess = None
            if row["road_excess_seconds"] and row["road_observed_at"]:
                value = float(row["road_excess_seconds"])
                if (
                    math.isfinite(value)
                    and value > 0
                    and 0 <= as_of - epoch(row["road_observed_at"]) <= 1800
                ):
                    excess = value
            trial = Trial(
                day,
                trip,
                int(row["stop_sequence"]),
                row["stop_id"],
                row["agency_id"],
                row["route_id"],
                as_of,
                scheduled,
                excess,
                agency_time,
                has_update,
            )
            identity = (trial.key, as_of)
            if identity in seen:
                raise ValueError(f"Duplicate trial at capture time for {trial.key}")
            seen.add(identity)
            trials.append(trial)
    return trials


def outcomes(archive, trials):
    wanted = {t.key for t in trials}
    indexed = {}
    with zipfile.ZipFile(archive) as gtfs:
        for row in table(gtfs, "stop_observations.txt"):
            day, trip = row["trip_start_date"], row["trip_id"]
            if (
                row["schedule_relationship"] not in ("", "0", "SCHEDULED")
                or not row["observed_arrival_time"]
                or not row["scheduled_arrival_time"]
            ):
                continue
            # 511 appends a hexadecimal hash to a trip in its historic regional feed. Match by
            # service date, stop sequence and stop ID too. Ambiguous rows cannot be scored.
            daily_trip = re.sub(r":[0-9a-fA-F]{6,64}$", "", trip)
            for trial_trip in {trip, daily_trip}:
                key = (day, trial_trip, int(row["stop_sequence"]), row["stop_id"])
                if key in wanted:
                    indexed[key] = (
                        None
                        if key in indexed
                        else (
                            gtfs_epoch(day, row["scheduled_arrival_time"]),
                            gtfs_epoch(day, row["observed_arrival_time"]),
                            row.get("uncertainty", ""),
                        )
                    )
    return indexed


def error_stats(values):
    ordered = sorted(values)
    return {
        "n": len(ordered),
        "median_absolute_error_s": statistics.median(ordered),
        "p90_absolute_error_s": ordered[math.ceil(0.9 * len(ordered)) - 1],
    }


def score(trials, indexed, holdout, min_trips, max_uncertainty, skipped):
    training = defaultdict(lambda: defaultdict(list))
    test = defaultdict(list)
    first_holdout_forecast = min(
        (t.as_of for t in trials if t.day >= holdout), default=math.inf
    )
    for trial in trials:
        observed = indexed.get(trial.key)
        if observed is None or observed[0] != trial.scheduled:
            skipped["missing, ambiguous or schedule-mismatched observation"] += 1
            continue
        scheduled, actual, uncertainty = observed
        if actual <= trial.as_of or (
            uncertainty and int(uncertainty) > max_uncertainty
        ):
            skipped["past arrival or high observation uncertainty"] += 1
            continue
        if trial.day < holdout:
            if (
                actual < first_holdout_forecast
                and not trial.has_update
                and trial.road_excess
            ):
                ratio = (actual - scheduled) / trial.road_excess
                training[trial.group][(trial.day, trial.trip)].append(ratio)
        else:
            test[trial.group].append((trial, actual, uncertainty))
    report = []
    for group, cases in sorted(test.items()):
        fit = training[group]
        slope = None
        if len(fit) >= min_trips:
            slope = min(
                1.0,
                max(
                    0.0,
                    statistics.median(
                        statistics.median(ratios) for ratios in fit.values()
                    ),
                ),
            )
        errors = defaultdict(list)
        exposed_trips = set()
        for trial, actual, _ in cases:
            baseline = (
                trial.agency_time if trial.agency_time is not None else trial.scheduled
            )
            candidate = baseline
            if trial.agency_time is not None:
                errors["agency_prediction"].append(abs(baseline - actual))
            if not trial.has_update and trial.road_excess and slope is not None:
                candidate += slope * trial.road_excess
                exposed_trips.add((trial.day, trial.trip))
                errors["exposed_baseline"].append(abs(baseline - actual))
                errors["exposed_candidate"].append(abs(candidate - actual))
            errors["scheduled"].append(abs(trial.scheduled - actual))
            errors["agency_plus_schedule"].append(abs(baseline - actual))
            errors["agency_plus_road_estimate"].append(abs(candidate - actual))
        report.append(
            {
                "agency": group[0],
                "route": group[1],
                "lead": group[2],
                "training_trips": len(fit),
                "road_delay_factor": slope,
                "exposed_holdout_trips": len(exposed_trips),
                "unknown_observation_uncertainty": sum(not u for _, _, u in cases),
                "sufficient_exposed_sample": len(exposed_trips) >= min_trips,
                "errors": {
                    name: error_stats(series) for name, series in errors.items()
                },
            }
        )
    return {"holdout_start": holdout, "excluded": dict(skipped), "groups": report}


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("archive", type=Path, help="511 historic YYYY-MM-so GTFS ZIP")
    parser.add_argument(
        "trials", type=Path, help="Timestamped inputs on verified road alignments"
    )
    parser.add_argument(
        "--holdout", required=True, help="First held-out service date (YYYYMMDD)"
    )
    parser.add_argument("--min-trips", type=int, default=30)
    parser.add_argument(
        "--max-uncertainty",
        type=int,
        default=120,
        help="Maximum known observation uncertainty (seconds)",
    )
    args = parser.parse_args()
    datetime.strptime(args.holdout, "%Y%m%d")
    if args.min_trips < 1 or args.max_uncertainty < 0:
        parser.error("Positive sample size and nonnegative uncertainty required")
    skipped = Counter()
    trials = read_trials(args.trials, skipped)
    print(
        json.dumps(
            score(
                trials,
                outcomes(args.archive, trials),
                args.holdout,
                args.min_trips,
                args.max_uncertainty,
                skipped,
            ),
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
