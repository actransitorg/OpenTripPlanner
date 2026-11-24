package org.opentripplanner.ext.traffic511;

import java.time.Duration;
import org.opentripplanner.updater.spi.PollingGraphUpdaterParameters;

/** Settings for 511 Events and Work Zone Data Exchange. Only the environment variable name is stored. */
public record Traffic511Parameters(
  String configRef,
  Duration frequency,
  Duration maxAge,
  String apiKeyEnvironmentVariable
) implements PollingGraphUpdaterParameters {}
