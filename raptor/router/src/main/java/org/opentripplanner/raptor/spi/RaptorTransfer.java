package org.opentripplanner.raptor.spi;

import org.jspecify.annotations.Nullable;
import org.opentripplanner.utils.time.DurationUtils;

/**
 * Encapsulate a transfer path. A time-dependent candidate is resolved at the stop arrival
 * (departure in reverse searches) before the transfer is offered to the next round.
 */
public interface RaptorTransfer {
  /**
   * Stop index where the path arrives at. The journey origin, destination and transit path board
   * stop must be part of the context; hence not a member attribute of this type.
   */
  int stop();

  /**
   * The generalized cost of this transfer in centi-seconds.
   * <p>
   * This method is called many times, so care needs to be taken that the value is stored, not
   * calculated for each invocation.
   */
  int c1();

  /**
   * The time duration to walk or travel the path in seconds. This is not the entire duration from
   * the journey origin, but just stop to stop.
   */
  int durationInSeconds();

  /** Return the actual path and criteria at this search-direction time, or null if unavailable. */
  default @Nullable RaptorTransfer atTime(int time) {
    return this;
  }

  /** Lower bound used to sort transfer candidates and terminate a standard search's transfer loop. */
  default int minimumDurationInSeconds() {
    return durationInSeconds();
  }

  /** A resolved path may require the passenger to wait at the stop until its chosen departure. */
  default int departureAfter(int readyTime) {
    return readyTime;
  }

  /** Arrival time including any visit within a transfer. */
  default int arrivalAfter(int departureTime) {
    return departureTime + durationInSeconds();
  }

  /** Call this from toString */
  default String asString() {
    String duration = DurationUtils.durationToStr(durationInSeconds());
    return String.format("On-Street %s ~ %d", duration, stop());
  }
}
