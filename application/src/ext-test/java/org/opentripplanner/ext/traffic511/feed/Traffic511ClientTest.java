package org.opentripplanner.ext.traffic511.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class Traffic511ClientTest {

  @Test
  void resolvesFeedRoutesUnderTheConfiguredPrefix() {
    var prefix = "https://cache.example.invalid/custom/511";
    for (String base : List.of(prefix, prefix + "/")) {
      assertEquals(
        URI.create(prefix + "/traffic/events"),
        Traffic511Client.cacheBase(base).resolve("traffic/events")
      );
    }
  }

  @Test
  void rejectsMissingBaseAndUrlsContainingCredentials() {
    for (String value : List.of(
      "",
      "http://cache.example",
      "https://user@cache.example",
      "https://cache.example/?key=fixture"
    )) {
      assertThrows(IllegalArgumentException.class, () -> Traffic511Client.cacheBase(value));
    }
  }
}
