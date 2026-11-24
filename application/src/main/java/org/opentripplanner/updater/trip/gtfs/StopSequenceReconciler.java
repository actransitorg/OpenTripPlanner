package org.opentripplanner.updater.trip.gtfs;

import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import org.opentripplanner.transit.model.timetable.Timetable;
import org.opentripplanner.transit.model.timetable.TripTimes;

/** Repairs two observed feed conventions only when the full scheduled trip independently confirms
 * every supplied stop reference. The upstream message is immutable and native validation follows.
 */
final class StopSequenceReconciler {

  static TripUpdate reconcile(TripUpdate source, Timetable timetable, TripTimes<?> times) {
    var pattern = timetable.getPattern();
    var rows = source.getStopTimeUpdateList();
    boolean canonical = true;
    boolean ordinal = true;
    boolean ordered = true;
    int previous = -1;
    for (var row : rows) {
      if (
        !row.hasStopSequence() ||
        !row.hasStopId() ||
        row.getStopId().isBlank() ||
        row.getStopTimeProperties().hasAssignedStopId()
      ) {
        return source;
      }
      int sequence = row.getStopSequence();
      var pos = times.stopPositionForGtfsSequence(sequence);
      canonical &=
        pos.isPresent() && pattern.getStop(pos.getAsInt()).getId().getId().equals(row.getStopId());
      ordinal &=
        sequence > 0 &&
        sequence <= times.getNumStops() &&
        pattern
          .getStop(sequence - 1)
          .getId()
          .getId()
          .equals(row.getStopId());
      ordered &= sequence > previous;
      previous = sequence;
    }
    // Preserve canonical ordered data and avoid combining guesses about both order and identity.
    if ((canonical && ordered) || (ordered ? !ordinal : !canonical)) {
      return source;
    }
    var seen = new HashSet<String>();
    var repeated = new HashSet<String>();
    for (var stop : pattern.getStops()) {
      if (!seen.add(stop.getId().getId())) {
        repeated.add(stop.getId().getId());
      }
    }
    if (rows.stream().anyMatch(row -> repeated.contains(row.getStopId()))) {
      return source;
    }
    if (ordered) {
      var result = source.toBuilder();
      for (int i = 0; i < rows.size(); i++) {
        int position = rows.get(i).getStopSequence() - 1;
        result.getStopTimeUpdateBuilder(i).setStopSequence(times.gtfsSequenceOfStopIndex(position));
      }
      return result.build();
    }
    var sorted = rows
      .stream()
      .sorted(Comparator.comparingInt(StopTimeUpdate::getStopSequence))
      .toList();
    return chronologicalAndDistinct(sorted)
      ? source.toBuilder().clearStopTimeUpdate().addAllStopTimeUpdate(sorted).build()
      : source;
  }

  private static boolean chronologicalAndDistinct(List<StopTimeUpdate> rows) {
    long time = Long.MIN_VALUE;
    int lastSequence = -1;
    for (var row : rows) {
      if (row.getStopSequence() <= lastSequence) {
        return false;
      }
      lastSequence = row.getStopSequence();
      if (row.hasArrival()) {
        if (!row.getArrival().hasTime() || row.getArrival().getTime() < time) {
          return false;
        }
        time = row.getArrival().getTime();
      }
      if (row.hasDeparture()) {
        if (!row.getDeparture().hasTime() || row.getDeparture().getTime() < time) {
          return false;
        }
        time = row.getDeparture().getTime();
      }
    }
    return true;
  }
}
