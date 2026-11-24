package org.opentripplanner.routing.algorithm.raptoradapter.transit.request;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.opentripplanner.raptor.spi.RaptorTransfer;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.routing.cost.CostLimit;
import org.opentripplanner.routing.via.model.ViaCoordinateTransfer;
import org.opentripplanner.street.model.edge.Edge;
import org.opentripplanner.street.model.path.StreetPath;
import org.opentripplanner.street.model.traffic.StreetTrafficSnapshot;

/** Resolve both walks at their own departure time, including the passenger's visit at the via point. */
public final class TrafficAwareViaTransfer implements RaptorTransfer {

  public record Resolved(
    int stop,
    int c1,
    int durationInSeconds,
    StreetPath toVia,
    StreetPath fromVia
  ) implements RaptorTransfer {
    @Override
    public int arrivalAfter(int departure) {
      return (
        departure +
        Math.toIntExact(Duration.between(toVia.startTime(), fromVia.endTime()).toSeconds())
      );
    }
  }

  private final RouteRequest request;
  private final ViaCoordinateTransfer template;
  private final Instant timeZero;
  private final Duration visitDuration;
  private final Map<Integer, Optional<Resolved>> paths = new ConcurrentHashMap<>();

  public static RaptorTransfer of(
    RouteRequest request,
    ViaCoordinateTransfer template,
    Instant timeZero,
    Duration visit
  ) {
    if (
      request.streetTraffic() == StreetTrafficSnapshot.NONE ||
      !(
        TrafficAwareTransferService.mayAffect(request, template.fromEdges()) ||
        TrafficAwareTransferService.mayAffect(request, template.toEdges())
      )
    ) {
      return template;
    }
    return new TrafficAwareViaTransfer(request, template, timeZero, visit);
  }

  private TrafficAwareViaTransfer(
    RouteRequest request,
    ViaCoordinateTransfer template,
    Instant timeZero,
    Duration visit
  ) {
    this.request = request;
    this.template = template;
    this.timeZero = timeZero;
    this.visitDuration = visit;
  }

  @Override
  public int stop() {
    return template.stop();
  }

  @Override
  public int c1() {
    return template.c1();
  }

  @Override
  public int durationInSeconds() {
    return template.durationInSeconds();
  }

  @Override
  public @Nullable RaptorTransfer atTime(int time) {
    return paths.computeIfAbsent(time, this::resolve).orElse(null);
  }

  private Optional<Resolved> resolve(int time) {
    return route(template.fromEdges(), timeZero.plusSeconds(time)).flatMap(toVia ->
      route(template.toEdges(), toVia.endTime().plus(visitDuration)).map(fromVia ->
        new Resolved(
          stop(),
          CostLimit.toRaptorCost(toVia.weight() + fromVia.weight()),
          Math.toIntExact(toVia.duration().plus(fromVia.duration()).toSeconds()),
          toVia,
          fromVia
        )
      )
    );
  }

  private Optional<StreetPath> route(List<Edge> edges, Instant time) {
    if (edges == null || edges.isEmpty()) {
      return Optional.empty();
    }
    return TrafficAwareTransferService.route(
      request,
      time,
      false,
      edges.getFirst().getFromVertex(),
      edges.getLast().getToVertex()
    );
  }
}
