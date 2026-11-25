package org.opentripplanner.ext.traffic511.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.beust.jcommander.ParameterException;
import java.util.List;
import org.junit.jupiter.api.Test;

class Traffic511CaptureOptionsTest {

  @Test
  void acceptsTheCaptureModeOnEitherSideOfTheDirectory() {
    for (String flag : List.of("--regional-transit", "--refresh-transit")) {
      var before = Traffic511Capture.Options.parse(flag, "data dir");
      var after = Traffic511Capture.Options.parse("data dir", flag);
      assertEquals(List.of("data dir"), before.directories);
      assertEquals(before.directories, after.directories);
      assertEquals(flag.equals("--regional-transit"), before.regional);
      assertEquals(flag.equals("--refresh-transit"), before.refresh);
      assertEquals(before.regional, after.regional);
      assertEquals(before.refresh, after.refresh);
    }
    assertTrue(Traffic511Capture.Options.parse("-h").help);
    assertEquals(List.of("road"), Traffic511Capture.Options.parse("road").directories);
    assertEquals(List.of("-road"), Traffic511Capture.Options.parse("--", "-road").directories);
  }

  @Test
  void rejectsInvalidInvocationsBeforeOpeningAFeed() {
    assertThrows(ParameterException.class, () -> Traffic511Capture.Options.parse());
    assertThrows(ParameterException.class, () -> Traffic511Capture.Options.parse("a", "b"));
    assertThrows(ParameterException.class, () -> Traffic511Capture.Options.parse("--typo"));
    assertThrows(ParameterException.class, () ->
      Traffic511Capture.Options.parse("--regional-transit", "data", "--refresh-transit")
    );
  }
}
