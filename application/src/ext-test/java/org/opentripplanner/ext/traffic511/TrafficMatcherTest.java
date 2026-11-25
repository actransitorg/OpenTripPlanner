package org.opentripplanner.ext.traffic511;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.opentripplanner.core.model.i18n.I18NString;
import org.opentripplanner.ext.traffic511.feed.EventSchedule;
import org.opentripplanner.ext.traffic511.feed.PemsSpeeds;
import org.opentripplanner.ext.traffic511.feed.PemsStation;
import org.opentripplanner.ext.traffic511.feed.TrafficEvent;
import org.opentripplanner.ext.traffic511.feed.TrafficEvent.Direction;
import org.opentripplanner.ext.traffic511.feed.TrafficEvent.Effect;
import org.opentripplanner.ext.traffic511.feed.TrafficEvent.Facility;
import org.opentripplanner.framework.application.OtpAppException;
import org.opentripplanner.routing.graph.kryosupport.KryoTestHelper;
import org.opentripplanner.street.Scope;
import org.opentripplanner.street.geometry.GeometryUtils;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.linking.LinkingDirection;
import org.opentripplanner.street.model.OsmWayIdentity;
import org.opentripplanner.street.model.StreetTraversalPermission;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.edge.StreetEdgeBuilder;
import org.opentripplanner.street.model.traffic.StreetTrafficNotice;
import org.opentripplanner.street.model.traffic.StreetTrafficSnapshot;
import org.opentripplanner.street.model.vertex.SplitterVertex;
import org.opentripplanner.street.search.TraverseMode;

class TrafficMatcherTest {

  private static final double LAT = 37.83;
  private final Graph graph = new Graph();
  private final TrafficMatcher matcher = new TrafficMatcher(graph);
  private int label;

  @Test
  void trafficUpdatersRequireAtLeastOneStreetEdgeWithRoadIdentity() {
    var from = vertex(-122.301, LAT);
    var to = vertex(-122.300, LAT);
    var noStreets = assertThrows(OtpAppException.class, () ->
      TrafficMatcher.requireRoadIdentities(graph)
    );
    assertTrue(noStreets.getMessage().contains("no street edges"));

    new StreetEdgeBuilder<>()
      .withFromVertex(from)
      .withToVertex(to)
      .withGeometry(GeometryUtils.makeLineString(from.getCoordinate(), to.getCoordinate()))
      .withName("Freeway")
      .withMeterLength(100)
      .withPermission(StreetTraversalPermission.ALL)
      .buildAndConnect();
    var noIdentities = assertThrows(OtpAppException.class, () ->
      TrafficMatcher.requireRoadIdentities(graph)
    );
    assertTrue(noIdentities.getMessage().contains("no OSM road identities"));

    road(to, from, "motorway", "I 80");
    assertDoesNotThrow(() -> TrafficMatcher.requireRoadIdentities(graph));
  }

  @Test
  void roadIdentitySurvivesGraphSerialization() {
    var edge = road(vertex(-122.301, LAT), vertex(-122.300, LAT), "motorway", "I 80");
    assertEquals(edge.getWayIdentity(), KryoTestHelper.writeAndRead(edge).getWayIdentity());
  }

  @Test
  void straightReportCannotCoverAnUnreportedDogleg() {
    var from = vertex(-122.301, LAT);
    var to = vertex(-122.2990, LAT);
    var edge = new StreetEdgeBuilder<>()
      .withFromVertex(from)
      .withToVertex(to)
      .withName("I 80")
      .withGeometry(
        GeometryUtils.makeLineString(
          -122.301,
          LAT,
          -122.3001,
          LAT,
          -122.3001,
          LAT + .0007,
          -122.3000,
          LAT + .0007,
          -122.3000,
          LAT,
          -122.299,
          LAT
        )
      )
      .withWayIdentity(
        new OsmWayIdentity(1, null, "I 80", "motorway", null, null, null, null, null, null, null)
      )
      .withPermission(StreetTraversalPermission.ALL)
      .withCarSpeed(10)
      .withMeterLength(330)
      .buildAndConnect();
    graph.requestIndex();
    var report = event(
      Effect.FULL_CLOSURE,
      GeometryUtils.makeLineString(from.getCoordinate(), to.getCoordinate())
    );
    assertSame(StreetTrafficSnapshot.NONE, matcher.matchEvents(List.of(report)));
    assertFalse(matcher.matchEvents(List.of(report)).isClosed(edge, TraverseMode.CAR, 1000, 2000));
  }

  @Test
  void missingDirectionCanOnlyProduceANoticeOnTheIdentifiedCenterline() {
    var a = vertex(-122.301, LAT);
    var b = vertex(-122.300, LAT);
    var east = road(a, b, "residential", "I 80");
    var west = road(b, a, "residential", "I 80");
    graph.requestIndex();
    var base = event(Effect.FULL_CLOSURE, east.getGeometry());
    var unknown = new TrafficEvent(
      base.id(),
      base.roadName(),
      Direction.UNKNOWN,
      base.geometry(),
      base.effect(),
      base.facility(),
      0,
      base.schedule(),
      base.notice()
    );
    var snapshot = matcher.matchEvents(List.of(unknown));
    for (var edge : List.of(east, west)) {
      assertFalse(snapshot.isClosed(edge, TraverseMode.CAR, 1000, 2000));
      assertEquals(List.of(base.notice()), snapshot.notices(edge, TraverseMode.CAR, 1000, 2000));
    }
  }

  @Test
  void aSeparateSidewalkClosureLeavesTheRoadAndCyclewayOpen() {
    var a = vertex(-122.301, LAT);
    var b = vertex(-122.300, LAT);
    var geometry = GeometryUtils.makeLineString(a.getCoordinate(), b.getCoordinate());
    var sidewalk = new StreetEdgeBuilder<>()
      .withFromVertex(a)
      .withToVertex(b)
      .withGeometry(geometry)
      .withName("Freeway")
      .withMeterLength(100)
      .withPermission(StreetTraversalPermission.PEDESTRIAN)
      .withWayIdentity(
        new OsmWayIdentity(
          2,
          "Freeway",
          null,
          "footway",
          "sidewalk",
          null,
          null,
          null,
          null,
          null,
          null
        )
      )
      .buildAndConnect();
    var car = road(vertex(-122.301, LAT + .00008), vertex(-122.300, LAT + .00008), "primary", null);
    var bike = road(
      vertex(-122.301, LAT - .00008),
      vertex(-122.300, LAT - .00008),
      "cycleway",
      null
    );
    var source = event(Effect.FULL_CLOSURE, geometry);
    var closed = new TrafficEvent(
      source.id(),
      "Freeway",
      Direction.EAST,
      geometry,
      Effect.FULL_CLOSURE,
      Facility.SIDEWALK,
      0,
      source.schedule(),
      source.notice()
    );
    graph.requestIndex();
    var snapshot = matcher.matchEvents(List.of(closed));
    assertTrue(snapshot.isClosed(sidewalk, TraverseMode.WALK, 1000, 2000));
    assertFalse(snapshot.isClosed(car, TraverseMode.CAR, 1000, 2000));
    assertFalse(snapshot.isClosed(bike, TraverseMode.BICYCLE, 1000, 2000));
    var ambiguous = new TrafficEvent(
      source.id(),
      "Freeway",
      Direction.EAST,
      car.getGeometry(),
      Effect.FULL_CLOSURE,
      Facility.SIDEWALK,
      0,
      source.schedule(),
      source.notice()
    );
    assertSame(StreetTrafficSnapshot.NONE, matcher.matchEvents(List.of(ambiguous)));
  }

  @Test
  void closureFollowsConnectedDirectionAndHonorsPartialExtentAndTime() {
    var a = vertex(-122.301, LAT);
    var b = vertex(-122.300, LAT);
    var c = vertex(-122.299, LAT);
    var first = road(a, b, "motorway", "I 80");
    var second = road(b, c, "motorway", "I 80");
    var reverse = road(b, a, "motorway", "I 80");
    var ramp = road(
      vertex(-122.301, LAT + .00004),
      vertex(-122.300, LAT + .00004),
      "motorway_link",
      "I 580"
    );
    graph.requestIndex();
    var closureLine = GeometryUtils.makeLineString(-122.3005, LAT, -122.2995, LAT);
    var closure = event(Effect.FULL_CLOSURE, closureLine);
    var snapshot = matcher.matchEvents(List.of(closure));
    assertTrue(snapshot.isClosed(first, TraverseMode.CAR, 1000, 2000));
    assertTrue(snapshot.isClosed(second, TraverseMode.CAR, 1000, 2000));
    assertFalse(snapshot.isClosed(reverse, TraverseMode.CAR, 1000, 2000));
    assertFalse(snapshot.isClosed(ramp, TraverseMode.CAR, 1000, 2000));
    assertFalse(snapshot.isClosed(first, TraverseMode.CAR, 20000, 21000));
    assertFalse(snapshot.isClosed(first, TraverseMode.WALK, 1000, 2000));

    var firstSplit = first.splitNonDestructively(
      vertex(-122.30075, LAT),
      LinkingDirection.BIDIRECTIONAL
    );
    assertSame(first.getWayIdentity(), firstSplit.head().getWayIdentity());
    assertFalse(snapshot.isClosed(firstSplit.head(), TraverseMode.CAR, 1000, 2000));
    assertTrue(snapshot.isClosed(firstSplit.tail(), TraverseMode.CAR, 1000, 2000));
  }

  @Test
  void pointIsLocalNoticeAndParallelSameNameLinesAreAmbiguous() {
    var east = road(vertex(-122.301, LAT), vertex(-122.300, LAT), "motorway", "I 80");
    var parallel = road(
      vertex(-122.301, LAT + .00002),
      vertex(-122.300, LAT + .00002),
      "motorway_link",
      "I 80"
    );
    graph.requestIndex();
    var event = event(Effect.FULL_CLOSURE, east.getGeometry());
    var snapshot = matcher.matchEvents(List.of(event));
    assertFalse(snapshot.isClosed(east, TraverseMode.CAR, 1000, 2000));
    assertFalse(snapshot.isClosed(parallel, TraverseMode.CAR, 1000, 2000));

    var separate = road(vertex(-122.25, LAT), vertex(-122.249, LAT), "motorway", "I 80");
    graph.insert(separate, Scope.PERMANENT);
    var point = GeometryUtils.getGeometryFactory().createPoint(new Coordinate(-122.2495, LAT));
    var report = event(Effect.FULL_CLOSURE, point);
    var reported = matcher.matchEvents(List.of(report));
    assertFalse(reported.isClosed(separate, TraverseMode.CAR, 1000, 2000));
    assertEquals(
      List.of(report.notice()),
      reported.notices(separate, TraverseMode.CAR, 1000, 2000)
    );
    assertTrue(reported.notices(separate, TraverseMode.WALK, 1000, 2000).isEmpty());
  }

  @Test
  void measuredSpeedIsBoundedByStationValidityAndReversible() {
    var east = road(vertex(-122.301, LAT), vertex(-122.300, LAT), "motorway", "I 80");
    var ramp = road(
      vertex(-122.301, LAT + .00004),
      vertex(-122.300, LAT + .00004),
      "motorway_link",
      "I 80"
    );
    graph.requestIndex();
    var station = new PemsStation("1", "80", Direction.EAST, -122.3005, LAT, 5);
    var speeds = matcher.matchSpeeds(
      Map.of("1", station),
      new PemsSpeeds(Instant.ofEpochMilli(5000), Map.of("1", 5d)),
      Duration.ofSeconds(10)
    );
    // 50 m at 10 m/s, 50 m at 5 m/s. Both searches must recover the same 15 s.
    assertEquals(100d / 15, speeds.carSpeed(east, 0, false, 10), .001);
    assertEquals(100d / 15, speeds.carSpeed(east, 15000, true, 10), .001);
    assertEquals(10, speeds.carSpeed(east, 15000, false, 10), .001);
    assertEquals(10, speeds.carSpeed(ramp, 5000, false, 10), .001);
    assertEquals("1", speeds.notices(east, TraverseMode.CAR, 5000, 15000).getFirst().sourceId());
    var moved = new PemsStation("1", "80", Direction.EAST, -120, LAT, 5);
    var updated = matcher.matchSpeeds(
      Map.of("1", moved),
      new PemsSpeeds(Instant.ofEpochMilli(6000), Map.of("1", 4d)),
      Duration.ofSeconds(10)
    );
    assertSame(StreetTrafficSnapshot.NONE, updated);
  }

  @Test
  void numericDelayIsDistributedAcrossItsExtentOnce() {
    var a = vertex(-122.301, LAT);
    var b = vertex(-122.300, LAT);
    var c = vertex(-122.299, LAT);
    var first = road(a, b, "motorway", "I 80");
    var second = road(b, c, "motorway", "I 80");
    graph.requestIndex();
    var delay = event(
      Effect.REPORTED_DELAY,
      GeometryUtils.makeLineString(a.getCoordinate(), c.getCoordinate())
    );
    var snapshot = matcher.matchEvents(List.of(delay, delay));
    // Each half of the 200 m route takes 10 scheduled seconds plus half of the 4 s delay.
    assertEquals(100d / 12, snapshot.carSpeed(first, 1000, false, 10), .001);
    assertEquals(100d / 12, snapshot.carSpeed(second, 13000, true, 10), .001);
  }

  private TrafficEvent event(Effect effect, Geometry geometry) {
    return new TrafficEvent(
      "report",
      "I-80 E",
      Direction.EAST,
      geometry,
      effect,
      Facility.ROAD,
      4,
      EventSchedule.interval(Instant.EPOCH, Instant.ofEpochSecond(20)),
      new StreetTrafficNotice("fixture", "report", effect.name(), "I-80 E", Instant.EPOCH)
    );
  }

  private SplitterVertex vertex(double x, double y) {
    var v = new SplitterVertex(Integer.toString(label++), x, y, I18NString.of("node"));
    graph.addVertex(v);
    return v;
  }

  private StreetEdge road(SplitterVertex a, SplitterVertex b, String highway, String ref) {
    return new StreetEdgeBuilder<>()
      .withFromVertex(a)
      .withToVertex(b)
      .withGeometry(GeometryUtils.makeLineString(a.getCoordinate(), b.getCoordinate()))
      .withName("Freeway")
      .withCarSpeed(10)
      .withMeterLength(100)
      .withPermission(StreetTraversalPermission.ALL)
      .withLink(highway.endsWith("_link"))
      .withWayIdentity(
        new OsmWayIdentity(1, "Freeway", ref, highway, null, null, null, null, null, null, null)
      )
      .buildAndConnect();
  }
}
