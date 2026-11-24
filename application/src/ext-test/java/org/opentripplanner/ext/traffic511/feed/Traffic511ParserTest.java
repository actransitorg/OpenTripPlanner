package org.opentripplanner.ext.traffic511.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class Traffic511ParserTest {

  private static final String LINE = """
  {"type":"LineString","coordinates":[[-122.4,37.7],[-122.3,37.7]]}
  """;
  private static final String POINT = """
  {"type":"Point","coordinates":[-122.4,37.7]}
  """;
  private static final String PERIOD = """
  {"intervals":["2026-06-01T01:00Z/2026-06-02T01:00Z"]}
  """;

  @Test
  void activePointAndConflictingLaneEvidenceAreAdvisories() {
    assertEquals(TrafficEvent.Effect.ADVISORY, event(POINT, "Closed", "closed", PERIOD).effect());
    assertEquals(TrafficEvent.Effect.ADVISORY, event(LINE, "Open", "closed", PERIOD).effect());
    var closure = event(LINE, "CLOSED", "blocked", PERIOD);
    assertEquals(TrafficEvent.Effect.FULL_CLOSURE, closure.effect());
    assertEquals(TrafficEvent.Direction.NORTH, closure.direction());
    assertTrue(closure.schedule().singleInterval().isPresent());
    assertFalse(
      closure
        .schedule()
        .overlaps(Instant.parse("2026-05-30T00:00:00Z"), Instant.parse("2026-06-01T01:00:00Z"))
    );
    assertTrue(
      closure
        .schedule()
        .overlaps(Instant.parse("2026-06-01T01:00:00Z"), Instant.parse("2026-06-01T01:00:01Z"))
    );
  }

  @Test
  void recurrenceHandlesOvernightPacificTimeAndException() {
    var schedule = event(
      LINE,
      "CLOSED",
      "closed",
      """
      {
        "recurring_schedules":[{
          "start_date":"2026-07-01","end_date":"2026-07-31","days":[1],
          "daily_start_time":"23:00","daily_end_time":"05:30"
        }],
        "exceptions":["2026-07-14", "2026-07-21 09:00-09:30"]
      }
      """
    ).schedule();
    // The Tuesday following the first Monday falls in the overnight closure.
    assertTrue(active(schedule, "2026-07-07T09:00:00Z"));
    assertFalse(active(schedule, "2026-07-07T12:30:00Z"));
    assertFalse(active(schedule, "2026-07-14T09:00:00Z"));
    assertFalse(active(schedule, "2026-07-21T09:00:00Z"));
    assertTrue(active(schedule, "2026-07-21T16:10:00Z"));
  }

  @Test
  void limitsOnlyOpenEndedIncidentsToTheObservationHorizon() {
    var feed = """
    {"events":[{"id":"test","event_type":"INCIDENT","status":"ACTIVE","geography":%s,
    "roads":[{"name":"I-80","direction":"E","state":"CLOSED"}],
    "schedule":{"intervals":["2026-06-01T00:00Z/","2026-07-01T00:00Z/2026-07-02T00:00Z"]}}]}
    """.formatted(LINE);
    var cutoff = Instant.parse("2026-06-01T01:00:00Z");
    var schedule = Traffic511Parser.readEvents(input(feed), cutoff).events().getFirst().schedule();
    assertTrue(active(schedule, "2026-06-01T00:59:59Z"));
    assertFalse(active(schedule, "2026-06-01T01:00:00Z"));
    assertTrue(active(schedule, "2026-07-01T12:00:00Z"));
  }

  @Test
  void acceptsVerifiedRegionalPaginationOnly() {
    assertEquals(-1, Traffic511Parser.nextOffset(""));
    assertEquals(500, Traffic511Parser.nextOffset("/traffic/events/?api_key=synthetic&offset=500"));
    assertEquals(20, Traffic511Parser.nextOffset("https://api.511.org/Traffic/Events?offset=20"));
    for (String link : java.util.List.of(
      "//elsewhere.invalid/?api_key=synthetic&offset=1",
      "/some/other/api?offset=1",
      "/traffic/events?api_key=synthetic&offset=-1"
    )) {
      var error = assertThrows(IllegalArgumentException.class, () ->
        Traffic511Parser.nextOffset(link)
      );
      assertFalse(error.toString().contains("synthetic"));
    }
  }

  @Test
  void separatesWzdxRoadDetoursFromFacilityClosures() {
    var events = Traffic511Parser.readWorkZoneFeed(
      input(
        """
        {"type":"FeatureCollection","road_event_feed_info":{"version":"4.1","update_date":"2026-06-01T00:00:00Z"},
        "features":[%s,%s,%s]}
        """.formatted(
          zone("work-zone", "both", "all-lanes-closed"),
          zone("detour", "eastbound", "all-lanes-closed"),
          zone("work-zone", "undefined", "all-lanes-closed")
        )
      )
    );
    assertEquals(Instant.parse("2026-06-01T00:00:00Z"), events.issuedAt());
    var currentHeader = """
    {"type":"FeatureCollection","feed_info":{"version":"4.1","update_date":"2026-06-01T00:00:00Z"},"features":[]}
    """;
    assertEquals(
      events.issuedAt(),
      Traffic511Parser.readWorkZoneFeed(input(currentHeader)).issuedAt()
    );
    assertEquals(TrafficEvent.Effect.FULL_CLOSURE, events.events().get(0).effect());
    assertEquals(TrafficEvent.Facility.SIDEWALK, events.events().get(1).facility());
    assertEquals(TrafficEvent.Effect.DETOUR, events.events().get(2).effect());
    assertTrue(
      events
        .events()
        .subList(3, events.events().size())
        .stream()
        .allMatch(e -> e.effect() == TrafficEvent.Effect.ADVISORY)
    );
    assertThrows(IllegalArgumentException.class, () -> Traffic511Parser.readEvents(input("{}")));
  }

  private static String zone(String type, String direction, String impact) {
    return """
    {"id":"feature","type":"Feature","geometry":%s,"properties":{
     "core_details":{"event_type":"%s","direction":"%s","road_names":["US-101"]},
     "start_date":"2026-06-01T00:00:00Z","end_date":"2026-06-02T00:00:00Z",
     "vehicle_impact":"%s","lanes":[{"type":"sidewalk","status":"closed"}]}}
    """.formatted(LINE, type, direction, impact);
  }

  private static TrafficEvent event(
    String geometry,
    String state,
    String laneStatus,
    String schedule
  ) {
    String source = """
    {"events":[{"id":"511.org/fixture","status":"ACTIVE","geography":%s,
    "roads":[{"name":"I-80","direction":"NorthBound","state":"%s","+lane_type":"All lanes","+lane_status":"%s"}],
    "schedule":%s}],"pagination":{"offset":0}}
    """.formatted(geometry, state, laneStatus, schedule);
    return Traffic511Parser.readEvents(input(source)).events().getFirst();
  }

  private static boolean active(EventSchedule schedule, String time) {
    var start = Instant.parse(time);
    return schedule.overlaps(start, start.plusSeconds(1));
  }

  static ByteArrayInputStream input(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }
}
