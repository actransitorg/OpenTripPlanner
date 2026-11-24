package org.opentripplanner.ext.traffic511;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentripplanner.standalone.config.framework.json.JsonSupport.newNodeAdapterForTest;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.opentripplanner.standalone.config.routerconfig.UpdatersConfig;

class Traffic511ConfigTest {

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
            { "type": "traffic-511", "apiKeyEnvironmentVariable": "TEST_TOKEN" },
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
    assertEquals("TEST_TOKEN", events.getFirst().apiKeyEnvironmentVariable());
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
