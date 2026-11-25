package org.opentripplanner.ext.traffic511.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.opentripplanner.ext.traffic511.feed.Traffic511ParserTest.input;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PemsParserTest {

  @Test
  void readsLiveFormatAndDropsImputedOrMissingSpeeds() throws IOException {
    var received = Instant.parse("2026-11-24T16:39:00Z");
    var speeds = PemsParser.readSpeeds(
      input(
        """
        11/24/2026 08:25:00
        400000,266,.0579,64.2,110.39,1.7,64.2,,.02,40,100
        400001,300,.0579,55.2,110.39,1.7,64.2,,.02,40,0
        400002,220,.0579,,110.39,1.7,64.2,,.02,40,100
        400003,220,.0579,42,110.39,1.7,64.2,,.02,0,100
        400004,220,.0579,50,110.39,1.7,64.2,,.02,40,66.7
        """
      ),
      received
    );
    assertEquals(Instant.parse("2026-11-24T16:25:00Z"), speeds.observedAt());
    assertEquals(Set.of("400000", "400004"), speeds.metersPerSecond().keySet());
    assertEquals(50 * 0.44704, speeds.metersPerSecond().get("400004"), 0.000001);
    assertThrows(IOException.class, () ->
      PemsParser.readSpeeds(input("11/25/2026 08:25:00"), received)
    );
    assertThrows(IOException.class, () ->
      PemsParser.readSpeeds(input("11/24/2026 08:25:00\n400000,6"), received)
    );
  }

  @Test
  void keepsOnlyMainlineStationsInDistrictFour() throws IOException {
    var stations = PemsParser.readStations(
      input(
        """
        <pems><district id="3"><detector_stations>
        <vds id="5" type="ML" freeway_id="80" freeway_dir="E" longitude="-122.4" latitude="37.8"/>
        </detector_stations></district><district id="4"><detector_stations>
        <vds id="400000" type="ML" freeway_id="80" freeway_dir="E" longitude="-122.4" latitude="37.8" abs_pm="8.9"/>
        <vds id="400001" type="HV" freeway_id="80" freeway_dir="E" longitude="-122.4" latitude="37.8"/>
        </detector_stations></district></pems>
        """
      )
    );
    assertEquals(Set.of("400000"), stations.keySet());
    var station = stations.get("400000");
    assertEquals("80", station.route());
    assertEquals(TrafficEvent.Direction.EAST, station.direction());
    assertEquals(8.9, station.absolutePostmile());
  }
}
