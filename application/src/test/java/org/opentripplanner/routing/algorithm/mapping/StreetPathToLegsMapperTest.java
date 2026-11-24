package org.opentripplanner.routing.algorithm.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.opentripplanner.street.model.StreetTraversalPermission.ALL;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentripplanner._support.time.ZoneIds;
import org.opentripplanner.model.plan.Leg;
import org.opentripplanner.model.plan.leg.StreetLeg;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.service.streetdetails.internal.DefaultStreetDetailsRepository;
import org.opentripplanner.service.streetdetails.internal.DefaultStreetDetailsService;
import org.opentripplanner.street.geometry.GeometryUtils;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.edge.StreetEdgeBuilder;
import org.opentripplanner.street.model.path.StreetPath;
import org.opentripplanner.street.model.traffic.StreetTrafficNotice;
import org.opentripplanner.street.model.traffic.StreetTrafficSnapshot;
import org.opentripplanner.street.model.vertex.LabelledIntersectionVertex;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.street.search.request.StreetSearchRequest;
import org.opentripplanner.street.search.state.State;
import org.opentripplanner.street.search.state.TestStateBuilder;
import org.opentripplanner.transit.service.NoopSiteResolver;

class StreetPathToLegsMapperTest {

  @Test
  void excludesReportsThatAreActiveOnlyDuringATurnInBothSearchDirections() {
    var a = new LabelledIntersectionVertex("a", 0, 0, false, false);
    var b = new LabelledIntersectionVertex("b", .001, 0, false, false);
    var c = new LabelledIntersectionVertex("c", .002, 0, false, false);
    var ab = trafficTestEdge(a, b);
    var bc = trafficTestEdge(b, c);
    var notice = new StreetTrafficNotice("fixture", "N123", "ADVISORY", "Lane work", Instant.EPOCH);
    var traffic = new StreetTrafficSnapshot() {
      @Override
      public List<StreetTrafficNotice> notices(
        StreetEdge edge,
        TraverseMode mode,
        long from,
        long until
      ) {
        return until > 12000 && from < 38000 ? List.of(notice) : List.of();
      }
    };
    var request = StreetSearchRequest.of()
      .withMode(StreetMode.CAR)
      .withStartTime(Instant.EPOCH)
      .withIntersectionTraversalCalculator((v, from, to, mode, fromSpeed, toSpeed) -> 30)
      .withStreetTraffic(traffic)
      .build();
    var forward = new StreetPath(bc.traverse(ab.traverse(new State(a, request))[0])[0]);
    assertEquals(Instant.ofEpochSecond(50), forward.endTime());
    assertEquals(List.of(), forward.trafficNotices(0));
    var reverseRequest = request.copyOfReversed(forward.endTime()).build();
    var reverse = new StreetPath(ab.traverse(bc.traverse(new State(c, reverseRequest))[0])[0]);
    assertEquals(Instant.EPOCH, reverse.startTime());
    assertEquals(List.of(), reverse.trafficNotices(0));
    assertEquals(List.of(notice), reverse.subPath(0, 2).trafficNotices(20000));
  }

  @Test
  void trafficNoticesUseShiftedTraversalTimeAndDeduplicateAcrossEdges() {
    var a = new LabelledIntersectionVertex("a", 0, 0, false, false);
    var b = new LabelledIntersectionVertex("b", .001, 0, false, false);
    var c = new LabelledIntersectionVertex("c", .002, 0, false, false);
    var ab = trafficTestEdge(a, b);
    var bc = trafficTestEdge(b, c);
    var notice = new StreetTrafficNotice("fixture", "N123", "ADVISORY", "Lane work", Instant.EPOCH);
    var traffic = new StreetTrafficSnapshot() {
      @Override
      public List<StreetTrafficNotice> notices(
        StreetEdge edge,
        TraverseMode mode,
        long from,
        long until
      ) {
        return mode == TraverseMode.CAR && from >= 60000 && until > from
          ? List.of(notice)
          : List.of();
      }
    };
    var request = StreetSearchRequest.of()
      .withMode(StreetMode.CAR)
      .withStartTime(Instant.EPOCH)
      .withStreetTraffic(traffic)
      .build();
    var path = new StreetPath(bc.traverse(ab.traverse(new State(a, request))[0])[0]);
    var mapper = mapper();
    var original = mapper.map(path, RouteRequest.defaultValue());
    assertEquals(List.of(), original.getFirst().streetTrafficNotices());
    var shifted = mapper.map(
      path,
      RouteRequest.defaultValue(),
      Instant.ofEpochSecond(60).atZone(ZoneOffset.UTC)
    );
    assertEquals(List.of(notice), shifted.getFirst().streetTrafficNotices());
  }

  private static StreetEdge trafficTestEdge(
    LabelledIntersectionVertex a,
    LabelledIntersectionVertex b
  ) {
    return new StreetEdgeBuilder<>()
      .withFromVertex(a)
      .withToVertex(b)
      .withGeometry(GeometryUtils.makeLineString(a.getCoordinate(), b.getCoordinate()))
      .withName("Road")
      .withMeterLength(100)
      .withPermission(ALL)
      .withCarSpeed(10)
      .buildAndConnect();
  }

  @Test
  void testCarRentalPickUp() {
    var mapper = mapper();

    var state = TestStateBuilder.ofCarRental().streetEdge().pickUpCarFromStation().build();

    var legs = mapper.map(new StreetPath(state), RouteRequest.defaultValue());

    assertEquals(2, legs.size());
    assertEquals(TraverseMode.WALK, traverseMode(legs.get(0)));
    assertEquals(TraverseMode.CAR, traverseMode(legs.get(1)));
  }

  private TraverseMode traverseMode(Leg leg) {
    return ((StreetLeg) leg).getMode();
  }

  private StreetPathToLegsMapper mapper() {
    return new StreetPathToLegsMapper(
      new NoopSiteResolver(),
      ZoneIds.UTC,
      new DefaultStreetDetailsService(new DefaultStreetDetailsRepository()),
      1
    );
  }
}
