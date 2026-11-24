package org.opentripplanner.routing.algorithm.raptoradapter.router.street;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentripplanner.street.model.StreetTraversalPermission.ALL;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.model.GenericLocation;
import org.opentripplanner.place.api.NearbyStop;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.DefaultAccessEgress;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.StreetPathAvailability;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.street.geometry.GeometryUtils;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.edge.StreetEdgeBuilder;
import org.opentripplanner.street.model.traffic.StreetTrafficSnapshot;
import org.opentripplanner.street.model.vertex.IntersectionVertex;
import org.opentripplanner.street.model.vertex.LabelledIntersectionVertex;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.street.search.state.State;
import org.opentripplanner.streetadapter.StreetSearchRequestMapper;

class TrafficAccessEgressRouterTest {

  private static final long ZERO = Instant.parse("2026-03-08T08:00:00Z").toEpochMilli();
  private static final FeedScopedId STOP = new FeedScopedId("RG", "entrance");
  private final IntersectionVertex a = new LabelledIntersectionVertex("origin", 0, 0, false, false);
  private final IntersectionVertex b = new LabelledIntersectionVertex(
    "station",
    0.001,
    0,
    false,
    false
  );
  private final StreetEdge usual = streetEdge(40);
  private final StreetEdge detour = streetEdge(60);
  private final StreetTrafficSnapshot traffic = new StreetTrafficSnapshot() {
    @Override
    public void forEachClosure(
      StreetEdge edge,
      TraverseMode mode,
      long from,
      long until,
      ClosureConsumer callback
    ) {
      if (edge == usual) {
        callback.accept(ZERO + 60_000, ZERO + 120_000, 0.25, 0.75);
      }
    }

    @Override
    public boolean isClosed(StreetEdge edge, TraverseMode mode, long start, long end) {
      return (
        edge == usual &&
        start + (end - start) * .25 < ZERO + 120_000 &&
        start + (end - start) * .75 > ZERO + 60_000
      );
    }
  };

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void keepsUsualAndDetourToSameStopForForwardAndReverseStreetSearches(boolean egress) {
    var request = RouteRequest.of()
      .withFrom(GenericLocation.fromCoordinate(0, 0))
      .withTo(GenericLocation.fromCoordinate(0, 0.001))
      .withDateTime(Instant.ofEpochMilli(ZERO + (egress ? 400_000 : 0)))
      .withStreetTraffic(traffic)
      .buildRequest();
    var paths = TrafficAccessEgressRouter.find(
      request,
      new StreetPathAvailability.Horizon(traffic, ZERO, ZERO, ZERO + 400_000),
      r -> findBestPath(r, egress),
      (stop, availability) -> new DefaultAccessEgress(1, stop.state(), availability)
    );
    assertEquals(2, paths.size());
    var shortcut = paths.getFirst();
    assertTrue(shortcut.hasOpeningHours());
    assertEquals(40, shortcut.durationInSeconds());
    // The closed part is occupied 10..30 seconds into the usual 40-second path.
    assertEquals(30, shortcut.earliestDepartureTime(30));
    assertEquals(110, shortcut.earliestDepartureTime(31));
    assertEquals(70, shortcut.latestArrivalTime(71));
    assertEquals(150, shortcut.latestArrivalTime(150));
    var alternate = paths.getLast();
    assertFalse(alternate.hasOpeningHours());
    assertEquals(60, alternate.durationInSeconds());
    assertEquals(31, alternate.earliestDepartureTime(31));
    assertEquals(71, alternate.latestArrivalTime(71));
  }

  private Collection<NearbyStop> findBestPath(RouteRequest request, boolean reverse) {
    var streetRequest = StreetSearchRequestMapper.map(request)
      .withMode(StreetMode.WALK)
      .withWalk(w -> w.withSpeed(1))
      .withArriveBy(reverse)
      .build();
    var start = new State(reverse ? b : a, streetRequest);
    for (var edge : List.of(usual, detour)) {
      var result = edge.traverse(start);
      if (result.length > 0) {
        return List.of(NearbyStop.nearbyStopForState(result[0], STOP));
      }
    }
    return List.of();
  }

  private StreetEdge streetEdge(double length) {
    return new StreetEdgeBuilder<>()
      .withFromVertex(a)
      .withToVertex(b)
      .withName("Station path")
      .withPermission(ALL)
      .withMeterLength(length)
      .withGeometry(GeometryUtils.makeLineString(a.getCoordinate(), b.getCoordinate()))
      .buildAndConnect();
  }
}
