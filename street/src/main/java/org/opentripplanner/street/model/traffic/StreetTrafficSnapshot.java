package org.opentripplanner.street.model.traffic;

import java.util.List;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.search.TraverseMode;

/** Immutable conditions for a single journey's street searches, in either search direction. */
public interface StreetTrafficSnapshot {
  StreetTrafficSnapshot NONE = new StreetTrafficSnapshot() {
    @Override
    public boolean mayAffectTraversal(StreetEdge edge) {
      return false;
    }
  };

  /** Cheap prefilter for cached street paths. False guarantees the edge has no routing effects. */
  default boolean mayAffectTraversal(StreetEdge edge) {
    return true;
  }

  /** Reports applicable to the traveled portion, mode, and chronological occupancy interval. */
  default List<StreetTrafficNotice> notices(
    StreetEdge edge,
    TraverseMode mode,
    long startTimeMillis,
    long endTimeMillis
  ) {
    return List.of();
  }

  /**
   * Is the directed edge closed to this mode during its occupancy interval [start, end)?
   * Times are Unix milliseconds. A partial edge is supplied as traversed so its geometry can
   * be compared to the affected part of its parent.
   */
  default boolean isClosed(
    StreetEdge edge,
    TraverseMode mode,
    long startTimeMillis,
    long endTimeMillis
  ) {
    return false;
  }

  /**
   * Enumerate closures intersecting the time range and actual directed edge. A provider with
   * scheduled or partial closures should supply their exact temporal and spatial bounds.
   */
  default void forEachClosure(
    StreetEdge edge,
    TraverseMode mode,
    long fromMillis,
    long untilMillis,
    ClosureConsumer consumer
  ) {
    if (isClosed(edge, mode, fromMillis, untilMillis)) {
      consumer.accept(fromMillis, untilMillis, 0, 1);
    }
  }

  /** Fractions are measured from the beginning of the supplied edge in its travel direction. */
  @FunctionalInterface
  interface ClosureConsumer {
    void accept(long startMillis, long endMillis, double fromFraction, double toFraction);
  }

  /**
   * Effective driving speed in m/s over this edge. The time is the departure for a forward search
   * and the arrival for a backward search. A provider spanning a speed change should integrate
   * the distance over the applicable time intervals in the given direction.
   */
  default double carSpeed(StreetEdge edge, long timeMillis, boolean arriveBy, double baseSpeed) {
    return baseSpeed;
  }
}
