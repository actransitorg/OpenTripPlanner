package org.opentripplanner.ext.traffic511.feed;

import java.time.Instant;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/** Active times for an event. Traversal and closure intervals are half-open, in UTC. */
@FunctionalInterface
public interface EventSchedule {
  /** Visit clipped active intervals until the visitor accepts one, or the horizon is exhausted. */
  boolean visitIntervals(Instant start, Instant end, BiPredicate<Instant, Instant> visitor);

  default boolean overlaps(Instant start, Instant end) {
    return visitIntervals(start, end, (from, until) -> true);
  }

  /** Rules may overlap. The consumer should take the union of the returned intervals. */
  default void forEachInterval(Instant from, Instant until, BiConsumer<Instant, Instant> consumer) {
    visitIntervals(from, until, (start, end) -> {
      consumer.accept(start, end);
      return false;
    });
  }

  default Optional<Interval> singleInterval() {
    return Optional.empty();
  }

  record Interval(Instant start, Instant end) implements EventSchedule {
    public Interval {
      if (!end.isAfter(start)) {
        throw new IllegalArgumentException("Invalid traffic event interval");
      }
    }

    @Override
    public boolean visitIntervals(
      Instant from,
      Instant until,
      BiPredicate<Instant, Instant> visitor
    ) {
      if (
        !from.isBefore(end) || (from.equals(until) ? from.isBefore(start) : !until.isAfter(start))
      ) {
        return false;
      }
      return visitor.test(from.isAfter(start) ? from : start, until.isBefore(end) ? until : end);
    }

    @Override
    public Optional<Interval> singleInterval() {
      return Optional.of(this);
    }
  }

  static EventSchedule interval(Instant from, Instant until) {
    return new Interval(from, until);
  }
}
