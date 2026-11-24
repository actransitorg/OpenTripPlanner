package org.opentripplanner.ext.traffic511;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.function.Function;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.traffic.StreetTrafficNotice;
import org.opentripplanner.street.model.traffic.StreetTrafficSnapshot;
import org.opentripplanner.street.search.TraverseMode;

/**
 * Combines independently refreshed sources. Only the street writer updates this object. A routing
 * request takes one immutable view and uses it throughout the journey, even during a refresh.
 */
final class TrafficSnapshotPublisher {

  enum Source {
    EVENTS,
    WORK_ZONES,
    PEMS,
  }

  private final EnumMap<Source, Update> latest = new EnumMap<>(Source.class);

  Function<Instant, StreetTrafficSnapshot> replace(Source source, Update update) {
    latest.put(source, update);
    var updates = List.copyOf(latest.values());
    return now -> {
      var active = updates
        .stream()
        .filter(it -> now.isBefore(it.expiresAt()))
        .map(Update::traffic)
        .filter(it -> it != StreetTrafficSnapshot.NONE)
        .toList();
      return switch (active.size()) {
        case 0 -> StreetTrafficSnapshot.NONE;
        case 1 -> active.getFirst();
        default -> new Combined(active);
      };
    };
  }

  record Update(StreetTrafficSnapshot traffic, Instant expiresAt) {}

  private record Combined(List<StreetTrafficSnapshot> sources) implements StreetTrafficSnapshot {
    @Override
    public boolean mayAffectTraversal(StreetEdge edge) {
      for (var source : sources) {
        if (source.mayAffectTraversal(edge)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public void forEachClosure(
      StreetEdge edge,
      TraverseMode mode,
      long fromMillis,
      long untilMillis,
      ClosureConsumer consumer
    ) {
      for (var source : sources) {
        source.forEachClosure(edge, mode, fromMillis, untilMillis, consumer);
      }
    }

    @Override
    public List<StreetTrafficNotice> notices(
      StreetEdge edge,
      TraverseMode mode,
      long start,
      long end
    ) {
      return sources
        .stream()
        .flatMap(s -> s.notices(edge, mode, start, end).stream())
        .distinct()
        .toList();
    }

    @Override
    public boolean isClosed(
      StreetEdge edge,
      TraverseMode mode,
      long startTimeMillis,
      long endTimeMillis
    ) {
      for (var source : sources) {
        if (source.isClosed(edge, mode, startTimeMillis, endTimeMillis)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public double carSpeed(StreetEdge edge, long timeMillis, boolean arriveBy, double baseSpeed) {
      double speed = baseSpeed;
      for (var source : sources) {
        // Each source sees the original speed. Two reports of the same delay must not compound.
        speed = Math.min(speed, source.carSpeed(edge, timeMillis, arriveBy, baseSpeed));
      }
      return speed;
    }
  }
}
