package org.opentripplanner.ext.traffic511;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.opentripplanner.ext.traffic511.RoadGeometry.Span;
import org.opentripplanner.ext.traffic511.feed.TrafficEvent;
import org.opentripplanner.ext.traffic511.feed.TrafficEvent.Effect;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.traffic.StreetTrafficNotice;
import org.opentripplanner.street.model.traffic.StreetTrafficSnapshot;
import org.opentripplanner.street.search.TraverseMode;

final class EventSnapshot implements StreetTrafficSnapshot {

  record Impact(TrafficEvent event, Span span, double delaySecondsPerMeter, boolean advisoryOnly) {
    boolean appliesTo(TraverseMode mode) {
      return switch (event.facility()) {
        case ROAD -> mode == TraverseMode.CAR;
        case SIDEWALK -> mode == TraverseMode.WALK;
        case BIKE_LANE -> mode == TraverseMode.BICYCLE || mode == TraverseMode.SCOOTER;
      };
    }
  }

  private final Map<StreetEdge, List<Impact>> closures;
  private final Map<StreetEdge, List<Impact>> notices;
  private final SpeedSnapshot delays;

  EventSnapshot(Map<StreetEdge, List<Impact>> impacts) {
    Map<StreetEdge, List<Impact>> closures = new HashMap<>();
    Map<StreetEdge, List<Impact>> notices = new HashMap<>();
    Map<StreetEdge, List<SpeedSnapshot.Section>> delays = new HashMap<>();
    impacts.forEach((edge, entries) -> {
      notices.put(edge, List.copyOf(entries));
      var closed = entries
        .stream()
        .filter(i -> !i.advisoryOnly && i.event.effect() == Effect.FULL_CLOSURE)
        .toList();
      if (!closed.isEmpty()) {
        closures.put(edge, closed);
      }
      for (var impact : entries) {
        if (
          !impact.advisoryOnly &&
          impact.event.effect() == Effect.REPORTED_DELAY &&
          impact.appliesTo(TraverseMode.CAR)
        ) {
          impact.event
            .schedule()
            .singleInterval()
            .ifPresent(window ->
              delays
                .computeIfAbsent(edge, ignored -> new ArrayList<>())
                .add(
                  new SpeedSnapshot.Section(
                    impact.span,
                    Double.POSITIVE_INFINITY,
                    impact.delaySecondsPerMeter,
                    millis(window.start()),
                    millis(window.end()),
                    impact.event.notice()
                  )
                )
            );
        }
      }
    });
    this.closures = Map.copyOf(closures);
    this.notices = Map.copyOf(notices);
    this.delays = new SpeedSnapshot(delays);
  }

  @Override
  public List<StreetTrafficNotice> notices(
    StreetEdge edge,
    TraverseMode mode,
    long start,
    long end
  ) {
    var parent = RoadGeometry.parent(edge);
    var entries = notices.get(parent);
    if (entries == null) {
      return List.of();
    }
    var covered = Span.traversed(edge, parent);
    var result = new LinkedHashSet<StreetTrafficNotice>();
    for (var impact : entries) {
      var overlap = covered.intersection(impact.span);
      if (impact.appliesTo(mode) && overlap.size() > 0) {
        double scale = (end - start) / covered.size();
        var enters = Instant.ofEpochMilli(
          start + (long) Math.floor((overlap.from() - covered.from()) * scale)
        );
        var exits = Instant.ofEpochMilli(
          start + (long) Math.ceil((overlap.to() - covered.from()) * scale)
        );
        if (impact.event.schedule().overlaps(enters, exits)) {
          result.add(impact.event.notice());
        }
      }
    }
    return List.copyOf(result);
  }

  private static long millis(Instant instant) {
    if (instant.isAfter(Instant.ofEpochMilli(Long.MAX_VALUE))) {
      return Long.MAX_VALUE;
    }
    return instant.isBefore(Instant.ofEpochMilli(Long.MIN_VALUE))
      ? Long.MIN_VALUE
      : instant.toEpochMilli();
  }

  @Override
  public boolean mayAffectTraversal(StreetEdge edge) {
    return closures.containsKey(RoadGeometry.parent(edge)) || delays.mayAffectTraversal(edge);
  }

  @Override
  public void forEachClosure(
    StreetEdge edge,
    TraverseMode mode,
    long fromMillis,
    long untilMillis,
    ClosureConsumer consumer
  ) {
    var parent = RoadGeometry.parent(edge);
    var entries = closures.get(parent);
    if (entries == null || fromMillis >= untilMillis) {
      return;
    }
    var covered = Span.traversed(edge, parent);
    for (var impact : entries) {
      var overlap = covered.intersection(impact.span);
      if (impact.appliesTo(mode) && overlap.size() > 0) {
        double entryFraction = (overlap.from() - covered.from()) / covered.size();
        double exitFraction = (overlap.to() - covered.from()) / covered.size();
        impact.event
          .schedule()
          .forEachInterval(
            Instant.ofEpochMilli(fromMillis),
            Instant.ofEpochMilli(untilMillis),
            (start, end) -> consumer.accept(millis(start), millis(end), entryFraction, exitFraction)
          );
      }
    }
  }

  @Override
  public boolean isClosed(StreetEdge edge, TraverseMode mode, long start, long end) {
    var parent = RoadGeometry.parent(edge);
    var entries = closures.get(parent);
    if (entries == null) {
      return false;
    }
    var covered = Span.traversed(edge, parent);
    if (covered.size() <= 0) {
      return false;
    }
    for (var impact : entries) {
      var overlap = covered.intersection(impact.span);
      if (impact.appliesTo(mode) && overlap.size() > 0) {
        // Times supplied by a reverse search are chronological too.
        double scale = (end - start) / covered.size();
        var enters = Instant.ofEpochMilli(
          start + (long) Math.floor((overlap.from() - covered.from()) * scale)
        );
        var exits = Instant.ofEpochMilli(
          start + (long) Math.ceil((overlap.to() - covered.from()) * scale)
        );
        if (impact.event.schedule().overlaps(enters, exits)) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public double carSpeed(StreetEdge edge, long timeMillis, boolean arriveBy, double baseSpeed) {
    return delays.carSpeed(edge, timeMillis, arriveBy, baseSpeed);
  }
}
