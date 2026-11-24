package org.opentripplanner.ext.traffic511;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.opentripplanner.core.model.i18n.I18NString;
import org.opentripplanner.ext.traffic511.feed.Traffic511Parser;
import org.opentripplanner.street.geometry.GeometryUtils;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.linking.LinkingDirection;
import org.opentripplanner.street.model.OsmWayIdentity;
import org.opentripplanner.street.model.StreetTraversalPermission;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.edge.StreetEdgeBuilder;
import org.opentripplanner.street.model.vertex.SplitterVertex;
import org.opentripplanner.street.search.TraverseMode;

class OaklandRampReplayTest {

  private final Graph graph = new Graph();

  @Test
  void onlyTheReportedSectionOfTheBerkeleyMovementClosesOvernight() throws Exception {
    // Simplified OSM fork, using way 123867375 toward Berkeley and 119058993 toward San Francisco.
    var fork = vertex("fork", -122.2848269, 37.8272632);
    var berkeley = vertex("Berkeley branch", -122.2890458, 37.8268523);
    var sanFrancisco = vertex("San Francisco branch", -122.2894724, 37.8266600);
    var ramp = edge(
      fork,
      berkeley,
      123867375,
      "motorway",
      "I 580",
      -122.2848269,
      37.8272632,
      -122.2865076,
      37.8269092,
      -122.2873385,
      37.8267284,
      -122.2878517,
      37.8267050,
      -122.2882894,
      37.8267268,
      -122.2888810,
      37.8268160,
      -122.2890458,
      37.8268523
    );
    var other = edge(
      fork,
      sanFrancisco,
      119058993,
      "motorway_link",
      null,
      -122.2848269,
      37.8272632,
      -122.2894724,
      37.8266600
    );
    graph.requestIndex();
    try (
      var feed = Objects.requireNonNull(getClass().getResourceAsStream("oakland-ramp-wzdx.json"))
    ) {
      var snapshot = new TrafficMatcher(graph).matchEvents(Traffic511Parser.readWorkZones(feed));
      long active = Instant.parse("2026-11-25T08:15:00Z").toEpochMilli();
      assertTrue(snapshot.isClosed(ramp, TraverseMode.CAR, active, active + 15000));
      assertFalse(snapshot.isClosed(other, TraverseMode.CAR, active, active + 15000));
      long reopen = Instant.parse("2026-11-25T13:30:00Z").toEpochMilli();
      assertFalse(snapshot.isClosed(ramp, TraverseMode.CAR, reopen, reopen + 15000));
      var split = ramp.splitNonDestructively(
        vertex("downstream of work", -122.2888810, 37.8268160),
        LinkingDirection.BIDIRECTIONAL
      );
      assertTrue(snapshot.isClosed(split.head(), TraverseMode.CAR, active, active + 15000));
      assertFalse(snapshot.isClosed(split.tail(), TraverseMode.CAR, active, active + 15000));
    }
  }

  private SplitterVertex vertex(String label, double lon, double lat) {
    var vertex = new SplitterVertex(label, lon, lat, I18NString.of(label));
    graph.addVertex(vertex);
    return vertex;
  }

  private StreetEdge edge(
    SplitterVertex from,
    SplitterVertex to,
    long id,
    String highway,
    String ref,
    double... coordinates
  ) {
    return new StreetEdgeBuilder<>()
      .withFromVertex(from)
      .withToVertex(to)
      .withGeometry(GeometryUtils.makeLineString(coordinates))
      .withName("Interchange")
      .withWayIdentity(
        new OsmWayIdentity(id, null, ref, highway, null, "1", "yes", null, null, null, null)
      )
      .withPermission(StreetTraversalPermission.CAR)
      .withMeterLength(380)
      .withCarSpeed(25)
      .buildAndConnect();
  }
}
