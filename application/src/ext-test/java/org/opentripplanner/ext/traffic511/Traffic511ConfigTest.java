package org.opentripplanner.ext.traffic511;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentripplanner.standalone.config.framework.json.JsonSupport.newNodeAdapterForTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentripplanner.framework.application.OTPFeature;
import org.opentripplanner.framework.application.OtpAppException;
import org.opentripplanner.standalone.config.CommandLineParameters;
import org.opentripplanner.standalone.config.routerconfig.UpdatersConfig;
import org.opentripplanner.standalone.configure.LoadApplication;
import org.opentripplanner.test.support.ResourceLoader;

class Traffic511ConfigTest {

  @Test
  void rejectTransitOnlyBuildWithRoadUpdater(@TempDir Path directory) throws IOException {
    var gtfs = ResourceLoader.of(getClass()).file(
      "/org/opentripplanner/street/integration/vvs-bus-751-only.gtfs.zip"
    );
    Files.copy(gtfs.toPath(), directory.resolve("bus.gtfs.zip"));
    Files.writeString(
      directory.resolve("otp-config.json"),
      """
      {"otpFeatures":{"Traffic511":true}}
      """
    );
    Files.writeString(
      directory.resolve("build-config.json"),
      """
      {"transitServiceStart":"2020-03-01","transitServiceEnd":"2020-03-31"}
      """
    );
    Files.writeString(
      directory.resolve("router-config.json"),
      """
      {"updaters":[{"type":"caltrans-pems"}]}
      """
    );
    var cli = new CommandLineParameters();
    cli.build = true;
    cli.save = true;
    cli.baseDirectory = List.of(directory.toFile());
    OTPFeature.Traffic511.testOn(() -> {
      var app = new LoadApplication(cli);
      app.validateConfigAndDataSources();
      var builder = app.appConstruction().createGraphBuilder();
      var error = assertThrows(OtpAppException.class, builder::run);
      assertTrue(error.getMessage().contains("no street edges"));
    });
  }

  @Test
  void rejectZeroFreshnessWindow() {
    var config = newNodeAdapterForTest(
      """
      {"updaters":[{"type":"caltrans-pems","maxAge":"0s"}]}
      """
    );
    assertThrows(IllegalArgumentException.class, () -> new UpdatersConfig(config));
  }

  @Test
  void readUpdaterConfiguration() {
    var config = new UpdatersConfig(
      newNodeAdapterForTest(
        """
        {
          "updaters": [
            { "type": "traffic-511", "cacheApiKeyEnvironmentVariable": "TEST_TOKEN" },
            { "type": "caltrans-pems", "maxAge": "15m" },
            { "type": "traffic-511-replay", "directory": "capture" },
            { "type": "traffic-511-replay", "directory": "capture", "includeSpeeds": false }
          ]
        }
        """
      )
    );
    var events = config.getTraffic511Parameters();
    var pems = config.getPemsParameters();
    var replays = config.getTrafficReplayParameters();
    assertEquals("TEST_TOKEN", events.getFirst().cacheApiKeyEnvironmentVariable());
    assertEquals(Duration.ofMinutes(10), events.getFirst().frequency());
    assertEquals(Duration.ofMinutes(15), pems.getFirst().maxAge());
    assertEquals(Path.of("capture"), replays.getFirst().directory());
    assertTrue(replays.getFirst().includeSpeeds());
    assertFalse(replays.getLast().includeSpeeds());
    assertThrows(IllegalArgumentException.class, () ->
      TrafficUpdaters.create(null, events, pems, replays)
    );
  }
}
