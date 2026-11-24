package org.opentripplanner.ext.traffic511;

import java.time.Duration;
import org.opentripplanner.updater.spi.PollingGraphUpdaterParameters;

/** Settings for Caltrans District 4 detector observations. */
public record PemsParameters(
  String configRef,
  Duration frequency,
  Duration maxAge
) implements PollingGraphUpdaterParameters {}
