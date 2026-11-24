package org.opentripplanner.ext.traffic511;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.opentripplanner.ext.traffic511.TrafficSnapshotPublisher.Source;
import org.opentripplanner.ext.traffic511.TrafficSnapshotPublisher.Update;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.traffic.StreetTrafficSnapshot;
import org.opentripplanner.street.search.TraverseMode;

class TrafficSnapshotPublisherTest {

  private static final Instant NOW = Instant.parse("2026-11-24T16:25:00Z");

  @Test
  void requestKeepsItsSnapshotAcrossAWriterRefresh() {
    var graph = new Graph();
    var publisher = new TrafficSnapshotPublisher();
    var original = new StreetTrafficSnapshot() {};
    graph.setStreetTrafficSnapshotSource(
      publisher.replace(Source.EVENTS, new Update(original, Instant.MAX))
    );

    var requestSnapshot = graph.streetTrafficSnapshot();
    CompletableFuture.runAsync(() ->
      graph.setStreetTrafficSnapshotSource(
        publisher.replace(Source.EVENTS, new Update(StreetTrafficSnapshot.NONE, Instant.MAX))
      )
    ).join();

    assertSame(original, requestSnapshot);
    assertSame(StreetTrafficSnapshot.NONE, graph.streetTrafficSnapshot());
  }

  @Test
  void refreshAndExpiryAreIndependentAndPinned() {
    var publisher = new TrafficSnapshotPublisher();
    var closed = new StreetTrafficSnapshot() {
      @Override
      public boolean isClosed(StreetEdge edge, TraverseMode mode, long start, long end) {
        return mode == TraverseMode.CAR;
      }
    };
    var congested = new StreetTrafficSnapshot() {
      @Override
      public double carSpeed(StreetEdge edge, long timeMillis, boolean arriveBy, double baseSpeed) {
        return baseSpeed / 2;
      }
    };
    publisher.replace(Source.WORK_ZONES, new Update(closed, NOW.plusSeconds(600)));
    var first = publisher.replace(Source.PEMS, new Update(congested, NOW.plusSeconds(300)));
    var pinned = first.apply(NOW);
    var cleared = publisher.replace(Source.WORK_ZONES, new Update(StreetTrafficSnapshot.NONE, NOW));

    assertTrue(pinned.isClosed(null, TraverseMode.CAR, 0, 1));
    assertFalse(pinned.isClosed(null, TraverseMode.WALK, 0, 1));
    assertEquals(5, pinned.carSpeed(null, 0, false, 10));
    assertSame(closed, first.apply(NOW.plusSeconds(300)));
    assertSame(congested, cleared.apply(NOW));
    assertSame(StreetTrafficSnapshot.NONE, cleared.apply(NOW.plusSeconds(300)));
  }
}
