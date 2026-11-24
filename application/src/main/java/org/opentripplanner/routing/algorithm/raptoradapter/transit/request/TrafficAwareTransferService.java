package org.opentripplanner.routing.algorithm.raptoradapter.transit.request;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.opentripplanner.astar.strategy.DurationSkipEdgeStrategy;
import org.opentripplanner.framework.application.OTPRequestTimeoutException;
import org.opentripplanner.place.api.NearbyStop;
import org.opentripplanner.raptor.data.transfers.regular.RaptorRegularTransferService;
import org.opentripplanner.raptor.spi.RaptorTransfer;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.routing.cost.CostLimit;
import org.opentripplanner.street.model.edge.Edge;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.path.StreetPath;
import org.opentripplanner.street.model.traffic.StreetTrafficSnapshot;
import org.opentripplanner.street.model.vertex.Vertex;
import org.opentripplanner.street.search.EuclideanRemainingWeightHeuristic;
import org.opentripplanner.street.search.StreetSearchBuilder;
import org.opentripplanner.streetadapter.StreetSearchRequestMapper;
import org.opentripplanner.transit.transfer.regular.api.RegularTransfersService;

/** A request-local view of the shared regular-transfer table, using the pinned traffic snapshot. */
public final class TrafficAwareTransferService implements RaptorRegularTransferService {

  public record Resolved(
    int stop,
    int c1,
    int durationInSeconds,
    int departureTime,
    StreetPath path
  ) implements RaptorTransfer {
    @Override
    public int departureAfter(int readyTime) {
      return Math.max(readyTime, departureTime);
    }
  }

  private record Fixed(int stop, int c1, int durationInSeconds) implements RaptorTransfer {}

  private final RegularTransfersService<NearbyStop> transfers;
  private final RaptorRegularTransferService table;
  private final RouteRequest request;
  private final Instant timeZero;
  private final Map<Integer, List<RaptorTransfer>> forward = new ConcurrentHashMap<>();
  private final Map<Integer, List<RaptorTransfer>> reverse = new ConcurrentHashMap<>();

  public static RaptorRegularTransferService of(
    RegularTransfersService<NearbyStop> transfers,
    RouteRequest request,
    ZonedDateTime timeZero
  ) {
    var table = transfers.raptorTransfers();
    return request.streetTraffic() == StreetTrafficSnapshot.NONE
      ? table
      : new TrafficAwareTransferService(transfers, table, request, timeZero.toInstant());
  }

  private TrafficAwareTransferService(
    RegularTransfersService<NearbyStop> transfers,
    RaptorRegularTransferService table,
    RouteRequest request,
    Instant timeZero
  ) {
    this.transfers = transfers;
    this.table = table;
    this.request = request;
    this.timeZero = timeZero;
  }

  @Override
  public Iterator<RaptorTransfer> getTransfersFromStop(int stop) {
    return forward.computeIfAbsent(stop, key -> candidates(key, false)).iterator();
  }

  @Override
  public Iterator<RaptorTransfer> getTransfersToStop(int stop) {
    return reverse.computeIfAbsent(stop, key -> candidates(key, true)).iterator();
  }

  private List<RaptorTransfer> candidates(int stop, boolean arriveBy) {
    var entries = arriveBy ? table.getTransfersToStop(stop) : table.getTransfersFromStop(stop);
    var candidates = new ArrayList<RaptorTransfer>();
    while (entries.hasNext()) {
      var entry = entries.next();
      var from = arriveBy ? entry.stop() : stop;
      var to = arriveBy ? stop : entry.stop();
      var path = transfers.findPath(from, to);
      var fixed = new Fixed(entry.stop(), entry.c1(), entry.durationInSeconds());
      if (path != null && mayAffect(request, path.edges())) {
        candidates.add(
          new Candidate(
            fixed,
            path.edges().getFirst().getFromVertex(),
            path.edges().getLast().getToVertex(),
            arriveBy
          )
        );
      } else {
        candidates.add(fixed);
      }
    }
    candidates.sort(Comparator.comparingInt(RaptorTransfer::minimumDurationInSeconds));
    return List.copyOf(candidates);
  }

  static Optional<StreetPath> route(
    RouteRequest request,
    Instant time,
    boolean arriveBy,
    Vertex from,
    Vertex to
  ) {
    var streetRequest = StreetSearchRequestMapper.mapToTransferRequest(request)
      .withStartTime(time)
      .withArriveBy(arriveBy)
      .build();
    return StreetSearchBuilder.of()
      .withFrom(from)
      .withTo(to)
      .withRequest(streetRequest)
      .withHeuristic(new EuclideanRemainingWeightHeuristic())
      .withPreStartHook(OTPRequestTimeoutException::checkForTimeout)
      .withSkipEdgeStrategy(
        new DurationSkipEdgeStrategy<>(request.preferences().system().maxJourneyDuration())
      )
      .getPathsToTarget()
      .stream()
      .findFirst();
  }

  static boolean mayAffect(RouteRequest request, List<Edge> edges) {
    return (
      edges != null &&
      edges
        .stream()
        .anyMatch(
          edge ->
            edge instanceof StreetEdge street && request.streetTraffic().mayAffectTraversal(street)
        )
    );
  }

  private final class Candidate implements RaptorTransfer {

    private final Fixed original;
    private final Vertex from;
    private final Vertex to;
    private final boolean arriveBy;
    private final Map<Integer, Optional<Resolved>> paths = new ConcurrentHashMap<>();

    Candidate(Fixed original, Vertex from, Vertex to, boolean arriveBy) {
      this.original = original;
      this.from = from;
      this.to = to;
      this.arriveBy = arriveBy;
    }

    @Override
    public int stop() {
      return original.stop();
    }

    @Override
    public int c1() {
      return original.c1();
    }

    @Override
    public int durationInSeconds() {
      return original.durationInSeconds();
    }

    @Override
    public int minimumDurationInSeconds() {
      return 0;
    }

    @Override
    public @Nullable RaptorTransfer atTime(int time) {
      return paths.computeIfAbsent(time, this::route).orElse(null);
    }

    private Optional<Resolved> route(int time) {
      return TrafficAwareTransferService.route(
        request,
        timeZero.plusSeconds(time),
        arriveBy,
        from,
        to
      ).map(path -> {
        int duration = Math.toIntExact(path.duration().toSeconds());
        return new Resolved(
          stop(),
          CostLimit.toRaptorCostWholeSeconds(path.weight()),
          duration,
          arriveBy ? time - duration : time,
          path
        );
      });
    }
  }
}
