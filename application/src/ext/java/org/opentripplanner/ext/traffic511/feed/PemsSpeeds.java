package org.opentripplanner.ext.traffic511.feed;

import java.time.Instant;
import java.util.Map;

public record PemsSpeeds(Instant observedAt, Map<String, Double> metersPerSecond) {
  public PemsSpeeds {
    metersPerSecond = Map.copyOf(metersPerSecond);
  }
}
