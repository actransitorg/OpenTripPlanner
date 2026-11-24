package org.opentripplanner.ext.traffic511;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentripplanner.core.model.i18n.I18NString;
import org.opentripplanner.street.geometry.GeometryUtils;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.model.OsmWayIdentity;
import org.opentripplanner.street.model.StreetTraversalPermission;
import org.opentripplanner.street.model.edge.StreetEdgeBuilder;
import org.opentripplanner.street.model.vertex.SplitterVertex;
import org.opentripplanner.street.search.TraverseMode;

class TrafficReplayTest {

  @Test
  void publishOnlyACompleteCapture(@TempDir Path dir) throws IOException {
    var graph = new Graph();
    var from = new SplitterVertex("west", -122.301, 37.83, I18NString.of("west"));
    var to = new SplitterVertex("east", -122.300, 37.83, I18NString.of("east"));
    graph.addVertex(from);
    graph.addVertex(to);
    var edge = new StreetEdgeBuilder<>()
      .withFromVertex(from)
      .withToVertex(to)
      .withGeometry(GeometryUtils.makeLineString(from.getCoordinate(), to.getCoordinate()))
      .withMeterLength(88)
      .withName("Test Street")
      .withPermission(StreetTraversalPermission.ALL)
      .withWayIdentity(
        new OsmWayIdentity(
          1,
          "Test Street",
          null,
          "residential",
          null,
          null,
          null,
          null,
          null,
          null,
          null
        )
      )
      .buildAndConnect();
    graph.requestIndex();

    Files.writeString(dir.resolve("captured-at.txt"), "2026-11-24T16:30:00Z");
    Files.writeString(dir.resolve("events.json"), "{\"events\":[]}");
    Files.writeString(dir.resolve("stations.xml"), "<pems><district id=\"4\"/></pems>");
    Files.writeString(dir.resolve("speeds.txt"), "11/24/2026 08:25:00\n");
    Files.writeString(
      dir.resolve("work-zones.json"),
      """
      {
        "type": "FeatureCollection",
        "road_event_feed_info": {"version":"4.1","update_date":"2026-11-24T16:25:00Z"},
        "features": [{
          "id":"closure", "type":"Feature",
          "geometry":{"type":"LineString","coordinates":[[-122.301,37.83],[-122.300,37.83]]},
          "properties":{
            "core_details":{"road_names":["Test Street"],"direction":"eastbound","event_type":"work-zone"},
            "start_date":"2026-11-24T16:00:00Z","end_date":"2026-11-24T17:00:00Z",
            "event_status":"active","vehicle_impact":"all-lanes-closed"
          }
        }]
      }
      """
    );
    var updater = new TrafficReplay(
      new TrafficMatcher(graph),
      new TrafficReplay.Parameters("test", dir, Duration.ofMinutes(30), true)
    );
    updater.setup(task -> {
      task.run(() -> graph);
      return CompletableFuture.completedFuture(null);
    });
    var time = Instant.parse("2026-11-24T16:30:00Z").toEpochMilli();
    assertFalse(graph.streetTrafficSnapshot().isClosed(edge, TraverseMode.CAR, time, time + 1000));
    updater.runPolling();
    assertTrue(graph.streetTrafficSnapshot().isClosed(edge, TraverseMode.CAR, time, time + 1000));
    assertFalse(graph.streetTrafficSnapshot().isClosed(edge, TraverseMode.WALK, time, time + 1000));

    Files.delete(dir.resolve("speeds.txt"));
    var closuresOnly = TrafficReplay.load(
      new TrafficMatcher(graph),
      new TrafficReplay.Parameters("test", dir, Duration.ofMinutes(30), false)
    );
    assertTrue(closuresOnly.isClosed(edge, TraverseMode.CAR, time, time + 1000));
    assertThrows(IOException.class, updater::runPolling);

    // An empty Events page followed by a failed WZDx read must retain the previous closure.
    Files.delete(dir.resolve("work-zones.json"));
    assertThrows(IOException.class, updater::runPolling);
    assertTrue(graph.streetTrafficSnapshot().isClosed(edge, TraverseMode.CAR, time, time + 1000));
  }
}
