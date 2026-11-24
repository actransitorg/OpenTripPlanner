package org.opentripplanner.routing.algorithm.raptoradapter.router.street;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.place.api.NearbyStop;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.RoutingAccessEgress;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.StreetPathAvailability;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.street.model.edge.Edge;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.traffic.StreetTrafficNotice;
import org.opentripplanner.street.model.traffic.StreetTrafficSnapshot;
import org.opentripplanner.street.search.TraverseMode;

public final class TrafficAccessEgressRouter {

  private TrafficAccessEgressRouter() {}

  /**
   * Keep the usual path with its actual opening hours. If a closure can affect any such path,
   * also look for paths usable throughout the search horizon and paths open at the requested time.
   * The three searches share the original immutable observations and street speed estimates.
   */
  public static List<RoutingAccessEgress> find(
    RouteRequest request,
    StreetPathAvailability.Horizon horizon,
    Function<RouteRequest, Collection<NearbyStop>> search,
    BiFunction<NearbyStop, StreetPathAvailability, RoutingAccessEgress> mapper
  ) {
    var usualRequest = request
      .copyOf()
      .withStreetTraffic(new CandidateSnapshot(horizon, false))
      .buildRequest();
    var usual = search.apply(usualRequest);
    var result = new ArrayList<RoutingAccessEgress>();
    for (var stop : usual) {
      var path = mapper.apply(stop, horizon.availability(stop.state()));
      if (path != null) {
        result.add(path);
      }
    }
    if (result.stream().noneMatch(RoutingAccessEgress::hasOpeningHours)) {
      return result;
    }

    var seen = new HashSet<PathKey>();
    usual.forEach(stop -> seen.add(PathKey.of(stop)));
    var detourRequest = request
      .copyOf()
      .withStreetTraffic(new CandidateSnapshot(horizon, true))
      .buildRequest();
    for (var variant : List.of(request, detourRequest)) {
      for (var stop : search.apply(variant)) {
        if (seen.add(PathKey.of(stop))) {
          var path = mapper.apply(stop, horizon.availability(stop.state()));
          if (path != null) {
            result.add(path);
          }
        }
      }
    }
    return result;
  }

  private record PathKey(FeedScopedId stop, List<Edge> edges, List<TraverseMode> modes) {
    static PathKey of(NearbyStop stop) {
      var modes = new ArrayList<TraverseMode>();
      for (var s = stop.state(); s.getBackState() != null; s = s.getBackState()) {
        modes.add(s.getBackMode());
      }
      return new PathKey(stop.stopId(), stop.edges(), modes);
    }
  }

  private static final class CandidateSnapshot implements StreetTrafficSnapshot {

    private final StreetPathAvailability.Horizon horizon;
    private final boolean avoidClosures;

    CandidateSnapshot(StreetPathAvailability.Horizon horizon, boolean avoidClosures) {
      this.horizon = horizon;
      this.avoidClosures = avoidClosures;
    }

    @Override
    public boolean mayAffectTraversal(StreetEdge edge) {
      return horizon.traffic().mayAffectTraversal(edge);
    }

    @Override
    public boolean isClosed(StreetEdge edge, TraverseMode mode, long start, long end) {
      if (!avoidClosures || !horizon.traffic().mayAffectTraversal(edge)) {
        return false;
      }
      boolean[] found = { false };
      horizon
        .traffic()
        .forEachClosure(
          edge,
          mode,
          horizon.fromMillis(),
          horizon.untilMillis(),
          (a, b, from, to) -> found[0] = true
        );
      return found[0];
    }

    @Override
    public double carSpeed(StreetEdge edge, long time, boolean arriveBy, double baseSpeed) {
      return horizon.traffic().carSpeed(edge, time, arriveBy, baseSpeed);
    }

    @Override
    public List<StreetTrafficNotice> notices(
      StreetEdge edge,
      TraverseMode mode,
      long start,
      long end
    ) {
      return horizon.traffic().notices(edge, mode, start, end);
    }
  }
}
