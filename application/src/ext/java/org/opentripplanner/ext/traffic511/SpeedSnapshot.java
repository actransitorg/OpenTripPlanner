package org.opentripplanner.ext.traffic511;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.opentripplanner.ext.traffic511.RoadGeometry.Span;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.traffic.StreetTrafficNotice;
import org.opentripplanner.street.model.traffic.StreetTrafficSnapshot;
import org.opentripplanner.street.search.TraverseMode;

/** Integrates distance over the speed changes that a car encounters in space and time. */
final class SpeedSnapshot implements StreetTrafficSnapshot {

  record Section(
    Span span,
    double metersPerSecond,
    double delaySecondsPerMeter,
    long start,
    long end,
    StreetTrafficNotice notice
  ) {
    double speed(double baseSpeed) {
      return Math.min(metersPerSecond, 1 / (1 / baseSpeed + delaySecondsPerMeter));
    }
  }

  @Override
  public List<StreetTrafficNotice> notices(
    StreetEdge edge,
    TraverseMode mode,
    long start,
    long end
  ) {
    var parent = RoadGeometry.parent(edge);
    var parts = segments.get(parent);
    if (mode != TraverseMode.CAR || parts == null) {
      return List.of();
    }
    var covered = Span.traversed(edge, parent);
    var result = new LinkedHashSet<StreetTrafficNotice>();
    for (var part : parts) {
      var overlap = part.span.intersection(covered);
      if (overlap.size() <= 0) {
        continue;
      }
      double scale = (end - start) / covered.size();
      double enters = start + (overlap.from() - covered.from()) * scale;
      double exits = start + (overlap.to() - covered.from()) * scale;
      for (var section : part.sections) {
        if (
          enters < section.end &&
          exits > section.start &&
          section.speed(edge.getCarSpeed()) < edge.getCarSpeed()
        ) {
          result.add(section.notice);
        }
      }
    }
    return List.copyOf(result);
  }

  private record Segment(Span span, List<Section> sections) {}

  @Override
  public boolean mayAffectTraversal(StreetEdge edge) {
    return segments.containsKey(RoadGeometry.parent(edge));
  }

  private final Map<StreetEdge, List<Segment>> segments;

  SpeedSnapshot(Map<StreetEdge, List<Section>> sections) {
    Map<StreetEdge, List<Segment>> result = new HashMap<>();
    sections.forEach((edge, entries) -> {
      var offsets = new TreeSet<Double>(List.of(0d, 1d));
      for (var section : entries) {
        offsets.add(section.span.from());
        offsets.add(section.span.to());
      }
      var parts = new ArrayList<Segment>();
      double start = 0;
      for (double end : offsets) {
        if (start < end) {
          var span = new Span(start, end);
          parts.add(
            new Segment(
              span,
              entries
                .stream()
                .filter(s -> s.span.intersection(span).size() > 0)
                .toList()
            )
          );
        }
        start = end;
      }
      result.put(edge, List.copyOf(parts));
    });
    this.segments = Map.copyOf(result);
  }

  @Override
  public double carSpeed(StreetEdge edge, long timeMillis, boolean arriveBy, double baseSpeed) {
    var parent = RoadGeometry.parent(edge);
    var parts = segments.get(parent);
    if (parts == null || baseSpeed <= 0) {
      return baseSpeed;
    }
    var covered = Span.traversed(edge, parent);
    if (covered.size() <= 0 || edge.getDistanceMeters() <= 0) {
      return baseSpeed;
    }
    // In reverse search, reflect time so the integration still advances toward larger values.
    double time = arriveBy ? -((double) timeMillis) : timeMillis;
    double totalMillis = 0;
    for (var part : arriveBy ? parts.reversed() : parts) {
      double fraction = covered.intersection(part.span).size();
      if (fraction <= 0) {
        continue;
      }
      double distance = (edge.getDistanceMeters() * fraction) / covered.size();
      double millis = travelTime(part.sections, distance, time + totalMillis, arriveBy, baseSpeed);
      totalMillis += millis;
    }
    return (edge.getDistanceMeters() * 1000) / totalMillis;
  }

  private static double travelTime(
    List<Section> sections,
    double meters,
    double startTime,
    boolean reverse,
    double baseSpeed
  ) {
    double elapsed = 0;
    while (meters > 0) {
      double now = startTime + elapsed;
      double next = Double.POSITIVE_INFINITY;
      double speed = baseSpeed;
      for (var section : sections) {
        double start = reverse ? -((double) section.end) : section.start;
        double end = reverse ? -((double) section.start) : section.end;
        if (now < start) {
          next = Math.min(next, start);
        } else if (now < end) {
          next = Math.min(next, end);
          // Overlapping reports of the same congestion contribute the largest single effect.
          speed = Math.min(speed, section.speed(baseSpeed));
        }
      }
      double needed = (meters * 1000) / speed;
      if (now + needed <= next) {
        return elapsed + needed;
      }
      meters -= ((next - now) * speed) / 1000;
      elapsed = next - startTime;
    }
    return elapsed;
  }
}
