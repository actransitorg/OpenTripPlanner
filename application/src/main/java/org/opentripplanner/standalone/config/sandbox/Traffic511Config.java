package org.opentripplanner.standalone.config.sandbox;

import static org.opentripplanner.standalone.config.framework.json.OtpVersion.V2_11;

import java.nio.file.Path;
import java.time.Duration;
import org.opentripplanner.ext.traffic511.PemsParameters;
import org.opentripplanner.ext.traffic511.Traffic511Parameters;
import org.opentripplanner.ext.traffic511.TrafficReplay;
import org.opentripplanner.standalone.config.framework.json.NodeAdapter;

public class Traffic511Config {

  public static Traffic511Parameters events(String configRef, NodeAdapter c) {
    return new Traffic511Parameters(
      configRef,
      frequency(c, Duration.ofMinutes(10)),
      maxAge(c),
      c
        .of("cacheApiKeyEnvironmentVariable")
        .since(V2_11)
        .summary("Name of the environment variable containing the AC Transit Cache client key.")
        .asString("ACTRANSIT_CACHE_API_KEY")
    );
  }

  public static PemsParameters pems(String configRef, NodeAdapter c) {
    return new PemsParameters(configRef, frequency(c, Duration.ofMinutes(5)), maxAge(c));
  }

  private static Duration frequency(NodeAdapter c, Duration defaultValue) {
    var frequency = c
      .of("frequency")
      .since(V2_11)
      .summary("Interval between polls.")
      .asDuration(defaultValue);
    if (frequency.isNegative()) {
      throw new IllegalArgumentException("Traffic updater frequency must be zero or positive.");
    }
    return frequency;
  }

  public static TrafficReplay.Parameters replay(String configRef, NodeAdapter c) {
    var directory = c
      .of("directory")
      .since(V2_11)
      .summary("Local directory containing a complete traffic capture.")
      .asString();
    return new TrafficReplay.Parameters(
      configRef,
      Path.of(directory),
      maxAge(c),
      c
        .of("includeSpeeds")
        .since(V2_11)
        .summary("Include captured PeMS speeds when replaying road conditions.")
        .asBoolean(true)
    );
  }

  private static Duration maxAge(NodeAdapter c) {
    var maxAge = c
      .of("maxAge")
      .since(V2_11)
      .summary("How long to use the last complete update for routing.")
      .asDuration(Duration.ofMinutes(30));
    if (!maxAge.isPositive()) {
      throw new IllegalArgumentException("Traffic updater maxAge must be positive.");
    }
    return maxAge;
  }
}
