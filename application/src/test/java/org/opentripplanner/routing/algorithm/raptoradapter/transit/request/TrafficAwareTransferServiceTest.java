package org.opentripplanner.routing.algorithm.raptoradapter.transit.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentripplanner.core.model.i18n.I18NString;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.place.api.NearbyStop;
import org.opentripplanner.raptor.data.transfers.regular.RaptorRegularTransferService;
import org.opentripplanner.raptor.spi.RaptorTransfer;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.routing.via.model.ViaCoordinateTransfer;
import org.opentripplanner.street.geometry.GeometryUtils;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.street.model.StreetTraversalPermission;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.edge.StreetEdgeBuilder;
import org.opentripplanner.street.model.traffic.StreetTrafficSnapshot;
import org.opentripplanner.street.model.vertex.SplitterVertex;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.transit.transfer.regular.api.RegularTransfersService;

class TrafficAwareTransferServiceTest {

  private final SplitterVertex a = vertex("a", -122, 37);
  private final SplitterVertex b = vertex("b", -121.9995, 37);
  private final StreetEdge direct = edge(a, b, 50);

  @Test
  void choosesTheOpenPathAtTheTransferTimeAndCarriesItIntoTheResult() {
    var c = vertex("c", -121.99975, 37.0003);
    edge(a, c, 48);
    var detour = edge(c, b, 48);
    var service = service();
    var forward = service.getTransfersFromStop(0).next();
    var before = assertInstanceOf(TrafficAwareTransferService.Resolved.class, forward.atTime(0));
    assertSame(direct, before.path().lastState().getBackEdge());
    var closed = assertInstanceOf(TrafficAwareTransferService.Resolved.class, forward.atTime(150));
    assertSame(detour, closed.path().lastState().getBackEdge());
    assertEquals(150, closed.departureTime());
    assertEquals(Instant.ofEpochSecond(150), closed.path().startTime());
    assertSame(closed, forward.atTime(150));

    var reverse = service.getTransfersToStop(1).next();
    var backwards = assertInstanceOf(
      TrafficAwareTransferService.Resolved.class,
      reverse.atTime(195)
    );
    assertSame(detour, backwards.path().lastState().getBackEdge());
    assertEquals(0, backwards.stop());
    assertEquals(Instant.ofEpochSecond(195), backwards.path().endTime());
    assertEquals(backwards.departureTime(), backwards.departureAfter(0));
  }

  @Test
  void anImpassableTransferIsRejectedDuringSearch() {
    assertNull(service().getTransfersFromStop(0).next().atTime(150));
  }

  @Test
  void visitWaitCanMoveTheSecondWalkIntoOrOutOfAClosure() {
    var before = vertex("before", -122.0001, 37);
    var first = edge(before, a, 10);
    var via = new ViaCoordinateTransfer(
      a.toWgsCoordinate(),
      0,
      1,
      List.of(first),
      List.of(direct),
      60,
      60
    );
    var candidate = TrafficAwareViaTransfer.of(
      request(),
      via,
      Instant.EPOCH,
      Duration.ofSeconds(100)
    );
    assertNull(candidate.atTime(0));
    var result = assertInstanceOf(TrafficAwareViaTransfer.Resolved.class, candidate.atTime(110));
    assertEquals(result.toVia().endTime().plusSeconds(100), result.fromVia().startTime());
    assertEquals(110 + result.durationInSeconds() + 100, result.arrivalAfter(110));
  }

  private RaptorRegularTransferService service() {
    record Transfer(int stop, int c1, int durationInSeconds) implements RaptorTransfer {}
    var table = new RaptorRegularTransferService() {
      @Override
      public Iterator<? extends RaptorTransfer> getTransfersFromStop(int from) {
        return List.of(new Transfer(1, 5000, 50)).iterator();
      }

      @Override
      public Iterator<? extends RaptorTransfer> getTransfersToStop(int to) {
        return List.of(new Transfer(0, 5000, 50)).iterator();
      }
    };
    var path = new NearbyStop(new FeedScopedId("t", "b"), 50, List.of(direct), null);
    var transfers = new RegularTransfersService<NearbyStop>() {
      @Override
      public RaptorRegularTransferService raptorTransfers() {
        return table;
      }

      @Override
      public NearbyStop findPath(int from, int to) {
        return from == 0 && to == 1 ? path : null;
      }

      @Override
      public List<NearbyStop> findPathsFrom(int from) {
        return from == 0 ? List.of(path) : List.of();
      }
    };
    return TrafficAwareTransferService.of(
      transfers,
      request(),
      Instant.EPOCH.atZone(ZoneOffset.UTC)
    );
  }

  private RouteRequest request() {
    return RouteRequest.of()
      .withJourney(j -> j.withTransfer(StreetMode.WALK))
      .withStreetTraffic(
        new StreetTrafficSnapshot() {
          @Override
          public boolean mayAffectTraversal(StreetEdge edge) {
            return edge == direct;
          }

          @Override
          public boolean isClosed(StreetEdge edge, TraverseMode mode, long start, long end) {
            return edge == direct && start < 200000 && end > 100000;
          }
        }
      )
      .buildDefault();
  }

  private static SplitterVertex vertex(String id, double lon, double lat) {
    return new SplitterVertex(id, lon, lat, I18NString.of(id));
  }

  private static StreetEdge edge(SplitterVertex from, SplitterVertex to, double length) {
    return new StreetEdgeBuilder<>()
      .withFromVertex(from)
      .withToVertex(to)
      .withGeometry(GeometryUtils.makeLineString(from.getCoordinate(), to.getCoordinate()))
      .withName("walk")
      .withMeterLength(length)
      .withPermission(StreetTraversalPermission.PEDESTRIAN)
      .buildAndConnect();
  }
}
