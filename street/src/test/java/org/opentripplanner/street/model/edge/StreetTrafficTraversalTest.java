package org.opentripplanner.street.model.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.opentripplanner.street.model.StreetModelFactory.intersectionVertex;
import static org.opentripplanner.street.model.StreetModelFactory.streetEdgeBuilder;
import static org.opentripplanner.street.model.StreetTraversalPermission.ALL;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.opentripplanner.street.geometry.GeometryUtils;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.street.model.traffic.StreetTrafficSnapshot;
import org.opentripplanner.street.model.vertex.IntersectionVertex;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.street.search.request.StreetSearchRequest;
import org.opentripplanner.street.search.state.State;

class StreetTrafficTraversalTest {

  private final IntersectionVertex from = intersectionVertex("from", 0, 0);
  private final IntersectionVertex to = intersectionVertex("to", 0.001, 0);
  private final StreetEdge road = streetEdgeBuilder(from, to, 100, ALL)
    .withCarSpeed(10)
    .buildAndConnect();

  @Test
  void closuresCheckTheWholeOccupancyInBothDirections() {
    var closure = close(TraverseMode.CAR, 4_000, 6_000);
    assertEquals(0, traverse(road, StreetMode.CAR, false, 0, closure).length);
    assertEquals(0, traverse(road, StreetMode.CAR, true, 10, closure).length);
    assertEquals(1, traverse(road, StreetMode.CAR, false, 6, closure).length);
    assertEquals(1, traverse(road, StreetMode.CAR, true, 4, closure).length);
    assertEquals(1, traverse(road, StreetMode.WALK, false, 0, closure).length);
    var opposite = streetEdgeBuilder(to, from, 100, ALL).withCarSpeed(10).buildAndConnect();
    assertEquals(1, traverse(opposite, StreetMode.CAR, false, 0, closure).length);
  }

  @Test
  void partialTraversalUsesDirectedParentAndPartialDistance() {
    var midpoint = intersectionVertex("midpoint", 0.0005, 0);
    var partial = new TemporaryPartialStreetEdgeBuilder()
      .withParentEdge(road)
      .withFromVertex(from)
      .withToVertex(midpoint)
      .withGeometry(GeometryUtils.makeLineString(from.getCoordinate(), midpoint.getCoordinate()))
      .withName("road")
      .withMeterLength(50)
      .withCarSpeed(10)
      .buildAndConnect();
    var closure = close(TraverseMode.CAR, 5_000, 6_000);
    assertEquals(0, traverse(road, StreetMode.CAR, false, 0, closure).length);
    var open = traverse(partial, StreetMode.CAR, false, 0, closure);
    assertEquals(5, open[0].getElapsedTimeSeconds());
    assertEquals(0, traverse(partial, StreetMode.CAR, false, 2, closure).length);
  }

  @Test
  void dismountWhenOnlyRidingIsClosed() {
    var walkingBike = traverse(
      road,
      StreetMode.BIKE,
      false,
      0,
      close(TraverseMode.BICYCLE, 0, 99_000)
    );
    assertEquals(TraverseMode.WALK, walkingBike[0].getBackMode());
    assertEquals(true, walkingBike[0].isBackWalkingBike());
  }

  @Test
  void speedChangesDurationAndWeightAndIsPinnedAcrossRefreshAndReversal() {
    var slow = new StreetTrafficSnapshot() {
      @Override
      public double carSpeed(StreetEdge edge, long time, boolean arriveBy, double base) {
        return 5;
      }
    };
    var graph = new Graph();
    graph.setStreetTrafficSnapshotSource(now -> slow);
    var pinned = request(StreetMode.CAR, false, 0, graph.streetTrafficSnapshot());
    graph.setStreetTrafficSnapshotSource(now -> StreetTrafficSnapshot.NONE);
    var forward = road.traverse(new State(from, pinned))[0];
    var reverseRequest = pinned.copyOfReversed(Instant.ofEpochSecond(20)).build();
    var reverse = road.traverse(new State(to, reverseRequest))[0];
    assertEquals(20, forward.getElapsedTimeSeconds());
    assertEquals(20, forward.getWeight());
    assertEquals(0, reverse.getTimeMilliseconds());
    assertSame(slow, reverseRequest.streetTraffic());
    assertSame(StreetTrafficSnapshot.NONE, graph.streetTrafficSnapshot());
  }

  private StreetTrafficSnapshot close(TraverseMode closedMode, long start, long end) {
    return new StreetTrafficSnapshot() {
      @Override
      public boolean isClosed(StreetEdge edge, TraverseMode mode, long entry, long exit) {
        return edge.getParentEdge() == road && mode == closedMode && exit > start && entry < end;
      }
    };
  }

  private static State[] traverse(
    StreetEdge edge,
    StreetMode mode,
    boolean arriveBy,
    long time,
    StreetTrafficSnapshot traffic
  ) {
    var origin = arriveBy ? edge.getToVertex() : edge.getFromVertex();
    return edge.traverse(new State(origin, request(mode, arriveBy, time, traffic)));
  }

  private static StreetSearchRequest request(
    StreetMode mode,
    boolean arriveBy,
    long time,
    StreetTrafficSnapshot traffic
  ) {
    return StreetSearchRequest.of()
      .withMode(mode)
      .withArriveBy(arriveBy)
      .withStartTime(Instant.ofEpochSecond(time))
      .withCar(car -> car.withReluctance(1))
      .withStreetTraffic(traffic)
      .build();
  }
}
