package org.opentripplanner.routing.algorithm.raptoradapter.transit;

import static org.opentripplanner.raptor.spi.RaptorConstants.TIME_NOT_SET;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.traffic.StreetTrafficSnapshot;
import org.opentripplanner.street.search.state.State;

/** Closure intervals expressed in RAPTOR seconds at the start of an entire street path. */
public final class StreetPathAvailability {

  public static final StreetPathAvailability ALWAYS = new StreetPathAvailability(
    List.of(),
    Integer.MIN_VALUE,
    Integer.MAX_VALUE
  );

  private record Block(int startInclusive, int endExclusive) {}

  private final List<Block> blocks;
  private final int firstDeparture;
  private final int lastDeparture;

  private StreetPathAvailability(List<Block> blocks, int firstDeparture, int lastDeparture) {
    this.blocks = blocks;
    this.firstDeparture = firstDeparture;
    this.lastDeparture = lastDeparture;
  }

  public record Horizon(
    StreetTrafficSnapshot traffic,
    long timeZeroMillis,
    long fromMillis,
    long untilMillis
  ) {
    public StreetPathAvailability availability(State finalState) {
      var blocks = new ArrayList<Block>();
      long pathStart = finalState.getRequest().arriveBy()
        ? finalState.getTimeMilliseconds()
        : finalState.getRequest().startTime().toEpochMilli();

      for (var s = finalState; s.getBackState() != null; s = s.getBackState()) {
        if (s.getBackEdge() instanceof StreetEdge street && traffic.mayAffectTraversal(street)) {
          final var transition = s;
          traffic.forEachClosure(
            street,
            s.getBackMode(),
            fromMillis,
            untilMillis,
            (start, end, fromFraction, toFraction) -> {
              var movement = street.trafficOccupancy(transition);
              long length = movement.endMillis() - movement.startMillis();
              long offset = movement.startMillis() - pathStart;
              long enters = offset + (long) Math.floor(length * fromFraction);
              long exits = offset + (long) Math.ceil(length * toFraction);
              // [departure+enters,departure+exits) meets [start,end) for start-exits < departure < end-enters.
              int from = Math.toIntExact(Math.floorDiv(start - exits - timeZeroMillis, 1000) + 1);
              int until = Math.toIntExact(Math.ceilDiv(end - enters - timeZeroMillis, 1000));
              if (from < until && enters < exits) {
                blocks.add(new Block(from, until));
              }
            }
          );
        }
      }
      if (blocks.isEmpty()) {
        return ALWAYS;
      }
      blocks.sort(Comparator.comparingInt(Block::startInclusive));
      var merged = new ArrayList<Block>();
      for (var block : blocks) {
        if (!merged.isEmpty() && merged.getLast().endExclusive >= block.startInclusive) {
          var previous = merged.removeLast();
          merged.add(
            new Block(previous.startInclusive, Math.max(previous.endExclusive, block.endExclusive))
          );
        } else {
          merged.add(block);
        }
      }
      return new StreetPathAvailability(
        List.copyOf(merged),
        Math.toIntExact(Math.ceilDiv(fromMillis - timeZeroMillis, 1000)),
        Math.toIntExact(
          Math.floorDiv(untilMillis - timeZeroMillis, 1000) - finalState.getElapsedTimeSeconds()
        )
      );
    }
  }

  int earliestDeparture(int requested) {
    if (blocks.isEmpty()) {
      return requested;
    }
    int time = Math.max(requested, firstDeparture);
    for (var block : blocks) {
      if (time < block.startInclusive) {
        break;
      }
      time = Math.max(time, block.endExclusive);
    }
    return time > lastDeparture ? TIME_NOT_SET : time;
  }

  int latestArrival(int requested, int duration) {
    if (blocks.isEmpty()) {
      return requested;
    }
    int time = Math.min(requested - duration, lastDeparture);
    for (var block : blocks.reversed()) {
      if (time >= block.endExclusive) {
        break;
      }
      time = Math.min(time, block.startInclusive - 1);
    }
    return time < firstDeparture ? TIME_NOT_SET : time + duration;
  }

  @Override
  public boolean equals(Object obj) {
    return (
      obj instanceof StreetPathAvailability other &&
      firstDeparture == other.firstDeparture &&
      lastDeparture == other.lastDeparture &&
      blocks.equals(other.blocks)
    );
  }

  @Override
  public int hashCode() {
    return Objects.hash(blocks, firstDeparture, lastDeparture);
  }
}
