package org.opentripplanner.ext.traffic511;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.opentripplanner.ext.traffic511.RoadGeometry.Span;
import org.opentripplanner.ext.traffic511.feed.PemsSpeeds;
import org.opentripplanner.ext.traffic511.feed.PemsStation;
import org.opentripplanner.ext.traffic511.feed.TrafficEvent;
import org.opentripplanner.ext.traffic511.feed.TrafficEvent.Direction;
import org.opentripplanner.ext.traffic511.feed.TrafficEvent.Effect;
import org.opentripplanner.ext.traffic511.feed.TrafficEvent.Facility;
import org.opentripplanner.framework.application.OtpAppException;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.model.OsmWayIdentity;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.traffic.StreetTrafficNotice;
import org.opentripplanner.street.model.traffic.StreetTrafficSnapshot;
import org.opentripplanner.street.search.TraverseMode;

/** Matches named 511 line features and District 4 mainline stations against one graph. */
public final class TrafficMatcher {

  private static final double MAX_DISTANCE_METERS = 18;
  private static final double AMBIGUITY_METERS = 4;
  private static final double MAX_STATION_REACH_METERS = 250;
  private static final Pattern MANAGED_LANES_NAME = Pattern.compile(
    "\\b(HOV|CARPOOL|EXPRESS LANES?)\\b",
    Pattern.CASE_INSENSITIVE
  );
  private static final Pattern ROUTE = Pattern.compile(
    "(INTERSTATE|I|US|STATE ROUTE|SR|CA)[ -]*(\\d+[A-Z]?)"
  );
  private static final Pattern DIRECTION_SUFFIX = Pattern.compile(
    "\\s+(?:NORTHBOUND|SOUTHBOUND|WESTBOUND|EASTBOUND|[NSEW]B?)$"
  );
  private final Graph graph;
  private Map<String, PemsStation> stationDirectory = Map.of();
  private Map<String, MatchedEdge> stationFootprints = Map.of();

  public TrafficMatcher(Graph graph) {
    this.graph = graph;
  }

  public static void requireRoadIdentities(Graph graph) {
    boolean hasStreetEdges = false;
    for (var edge : graph.findEdges(StreetEdge.class)) {
      hasStreetEdges = true;
      if (edge.getWayIdentity() != null) {
        return;
      }
    }
    if (!hasStreetEdges) {
      throw new OtpAppException(
        "A Traffic511 updater is configured, but the graph contains no street edges. " +
          "Include a routable OSM extract in the street graph build."
      );
    }
    throw new OtpAppException(
      "A Traffic511 updater is configured, but the street edges have no OSM road identities. " +
        "Rebuild the street graph with Traffic511 enabled in otp-config.json."
    );
  }

  public StreetTrafficSnapshot matchEvents(List<TrafficEvent> events) {
    Map<StreetEdge, List<EventSnapshot.Impact>> impacts = new HashMap<>();
    for (var event : events) {
      if (event.effect() == Effect.DETOUR) {
        continue;
      }
      var geometry = event.geometry();
      if (geometry == null || geometry.isEmpty()) {
        continue;
      }
      if (geometry instanceof Point point) {
        // A point can support a local advisory, even when the provider calls the road closed.
        matchPointNotice(impacts, event, point);
        continue;
      }
      // A published delay applies to a whole movement. Multipart or bidirectional delays do not
      // specify how to divide it between those movements.
      boolean both = event.direction() == Direction.BOTH || event.direction() == Direction.UNKNOWN;
      boolean advisory =
        event.direction() == Direction.UNKNOWN ||
        (event.effect() == Effect.REPORTED_DELAY && (geometry.getNumGeometries() != 1 || both));
      for (int i = 0; i < geometry.getNumGeometries(); i++) {
        if (geometry.getGeometryN(i) instanceof LineString line && !line.isEmpty()) {
          var candidates = roads(line.getEnvelopeInternal(), identity ->
            matchesName(identity, event.roadName())
          );
          addMovement(
            impacts,
            event,
            matchLine(
              line,
              candidates,
              both ? Direction.BOTH : event.direction(),
              event.facility()
            ),
            advisory
          );
          if (both) {
            addMovement(
              impacts,
              event,
              matchLine(line.reverse(), candidates, Direction.BOTH, event.facility()),
              advisory
            );
          }
        }
      }
    }
    return impacts.isEmpty() ? StreetTrafficSnapshot.NONE : new EventSnapshot(impacts);
  }

  private void matchPointNotice(
    Map<StreetEdge, List<EventSnapshot.Impact>> impacts,
    TrafficEvent event,
    Point point
  ) {
    var target = RoadGeometry.project(point.getCoordinate());
    var candidates = roads(
      point.getEnvelopeInternal(),
      identity ->
        matchesName(identity, event.roadName()) && matchesFacility(identity, event.facility())
    );
    var hits = new ArrayList<Hit>();
    for (var road : candidates) {
      var hit = project(road, target);
      if (
        hit.distance <= MAX_DISTANCE_METERS &&
        (event.direction() == Direction.BOTH ||
          event.direction() == Direction.UNKNOWN ||
          agreesWithDirection(road.edge, event.direction(), road.geometry.tangent(hit.offset)))
      ) {
        hits.add(hit);
      }
    }
    if (hits.isEmpty()) {
      return;
    }
    hits.sort(Comparator.comparingDouble(Hit::distance));
    var best = hits.getFirst();
    var nearby = hits
      .stream()
      .filter(hit -> hit.distance <= best.distance + AMBIGUITY_METERS)
      .toList();
    // A report without a heading can be tied to both sides of a single OSM centerline. Separate
    // parallel or crossing ways, including a junction between blocks, cannot be distinguished.
    for (var hit : nearby) {
      if (
        hit != best &&
        (hit.road.identity.wayId() != best.road.identity.wayId() ||
          hit.road.edge.getFromVertex() != best.road.edge.getToVertex() ||
          hit.road.edge.getToVertex() != best.road.edge.getFromVertex() ||
          !hit.road.edge.getGeometry().equalsExact(best.road.edge.getGeometry().reverse()))
      ) {
        return;
      }
    }
    for (var selected : nearby) {
      double length = selected.road.geometry.length;
      var span = new Span(
        Math.max(0, (selected.offset - MAX_DISTANCE_METERS) / length),
        Math.min(1, (selected.offset + MAX_DISTANCE_METERS) / length)
      );
      impacts
        .computeIfAbsent(selected.road.edge, ignored -> new ArrayList<>())
        .add(new EventSnapshot.Impact(event, span, 0, true));
    }
  }

  private static void addMovement(
    Map<StreetEdge, List<EventSnapshot.Impact>> impacts,
    TrafficEvent event,
    List<MatchedEdge> movement,
    boolean advisoryOnly
  ) {
    double totalLength = 0;
    for (var part : movement) {
      totalLength += part.edge().getDistanceMeters() * part.span().size();
    }
    if (
      totalLength <= 0 ||
      (movement.stream().anyMatch(part -> part.edge().isLink()) &&
        !event.roadName().toUpperCase(Locale.ROOT).contains("RAMP"))
    ) {
      return;
    }
    for (var part : movement) {
      var impact = new EventSnapshot.Impact(
        event,
        part.span(),
        event.delaySeconds() / totalLength,
        advisoryOnly
      );
      impacts.computeIfAbsent(part.edge(), ignored -> new ArrayList<>()).add(impact);
    }
  }

  private record Road(StreetEdge edge, OsmWayIdentity identity, RoadGeometry geometry) {}

  private record MatchedEdge(StreetEdge edge, Span span) {}

  private record Hit(Road road, double offset, double distance) {}

  private List<Road> roads(Envelope bounds, Predicate<OsmWayIdentity> eligible) {
    var envelope = new Envelope(bounds);
    envelope.expandBy(RoadGeometry.LON_MARGIN, RoadGeometry.LAT_MARGIN);
    var roads = new ArrayList<Road>();
    for (var candidate : graph.findEdges(envelope)) {
      if (
        candidate instanceof StreetEdge edge &&
        edge.getWayIdentity() != null &&
        eligible.test(edge.getWayIdentity()) &&
        edge.getDistanceMeters() > 0
      ) {
        var geometry = new RoadGeometry(edge.getGeometry());
        if (geometry.length > 0) {
          roads.add(new Road(edge, edge.getWayIdentity(), geometry));
        }
      }
    }
    return roads;
  }

  /** Require a connected chain covering the complete line in travel order. */
  private static List<MatchedEdge> matchLine(
    LineString line,
    List<Road> roads,
    Direction direction,
    Facility facility
  ) {
    var feature = new RoadGeometry(line);
    if (roads.isEmpty() || feature.length < 5) {
      return List.of();
    }
    var offsets = new TreeSet<Double>();
    int steps = (int) Math.ceil(feature.length / 12);
    for (int i = 0; i <= steps; i++) {
      offsets.add((feature.length * i) / steps);
    }
    // An OSM block can be shorter than the sampling interval.
    for (var road : roads) {
      var midpoint = road.geometry.line.extractPoint(road.geometry.length / 2);
      double index = feature.line.project(midpoint);
      if (feature.line.extractPoint(index).distance(midpoint) <= MAX_DISTANCE_METERS) {
        offsets.add(index);
      }
    }
    List<MatchedEdge> path = new ArrayList<>();
    Hit first = null;
    Hit previous = null;
    for (double index : offsets) {
      var target = feature.line.extractPoint(index);
      var tangent = feature.tangent(index);
      var hits = new ArrayList<Hit>();
      double nearestParallel = Double.POSITIVE_INFINITY;
      double nearestOtherFacility = Double.POSITIVE_INFINITY;
      for (var road : roads) {
        var hit = project(road, target);
        if (hit.distance > MAX_DISTANCE_METERS) {
          continue;
        }
        var heading = road.geometry.tangent(hit.offset);
        double alignment = heading.x * tangent.x + heading.y * tangent.y;
        if (!matchesFacility(road.identity, facility)) {
          if (Math.abs(alignment) >= .7) {
            nearestOtherFacility = Math.min(nearestOtherFacility, hit.distance);
          }
          continue;
        }
        if (Math.abs(alignment) >= .7) {
          nearestParallel = Math.min(nearestParallel, hit.distance);
        }
        if (alignment < .7) {
          continue;
        }
        hits.add(hit);
      }
      var best = uniqueHit(hits, previous);
      if (
        best == null ||
        best.distance > nearestParallel + AMBIGUITY_METERS ||
        (facility != Facility.ROAD && best.distance + AMBIGUITY_METERS >= nearestOtherFacility)
      ) {
        return List.of();
      }
      if (previous != null && previous.road != best.road) {
        if (previous.road.edge.getToVertex() != best.road.edge.getFromVertex()) {
          return List.of();
        }
        path.add(
          new MatchedEdge(
            previous.road.edge,
            new Span(first.offset / first.road.geometry.length, 1)
          )
        );
        first = new Hit(best.road, 0, 0);
      } else if (first == null) {
        first = best;
      } else if (best.offset + .5 < previous.offset) {
        return List.of();
      }
      previous = best;
    }
    if (previous == null) {
      return List.of();
    }
    path.add(
      new MatchedEdge(
        previous.road.edge,
        new Span(
          first.offset / first.road.geometry.length,
          previous.offset / previous.road.geometry.length
        )
      )
    );
    if (direction != Direction.BOTH) {
      var from = feature.line.extractPoint(0);
      var to = feature.line.extractPoint(feature.length);
      for (var part : path) {
        if (
          !agreesWithDirection(part.edge(), direction, new Coordinate(to.x - from.x, to.y - from.y))
        ) {
          return List.of();
        }
      }
    }
    // A line sampled on the feed side can skip a hairpin or a loop on the OSM side. Verify the
    // actual selected subcurves in the opposite direction before any part can affect routing.
    double frontier = 0;
    for (var part : path) {
      var road = new RoadGeometry(part.edge().getGeometry());
      double begin = part.span().from() * road.length;
      double end = part.span().to() * road.length;
      int samples = Math.max(1, (int) Math.ceil((end - begin) / 8));
      for (int i = 0; i <= samples; ++i) {
        var point = road.line.extractPoint(begin + ((end - begin) * i) / samples);
        double offset = feature.line.project(point);
        if (
          offset + 2 < frontier ||
          feature.line.extractPoint(offset).distance(point) > MAX_DISTANCE_METERS
        ) {
          return List.of();
        }
        frontier = Math.max(frontier, offset);
      }
    }
    return path;
  }

  private static Hit project(Road road, Coordinate point) {
    double offset = road.geometry.line.project(point);
    return new Hit(road, offset, road.geometry.line.extractPoint(offset).distance(point));
  }

  private static @Nullable Hit uniqueHit(List<Hit> hits, @Nullable Hit previous) {
    if (hits.isEmpty()) {
      return null;
    }
    hits.sort(Comparator.comparingDouble(Hit::distance));
    Hit best = hits.getFirst();
    for (int i = 1; i < hits.size(); i++) {
      var other = hits.get(i);
      if (other.distance > best.distance + AMBIGUITY_METERS) {
        break;
      }
      var a = best.road.edge;
      var b = other.road.edge;
      if (a.getToVertex() != b.getFromVertex() && b.getToVertex() != a.getFromVertex()) {
        return null;
      }
      // Either edge is valid at their shared endpoint; prefer continuing the current one on ties.
      if (
        previous != null && other.road == previous.road && other.distance <= best.distance + .01
      ) {
        best = other;
      }
    }
    return best;
  }

  private static boolean agreesWithDirection(
    StreetEdge edge,
    Direction direction,
    Coordinate vector
  ) {
    var identity = edge.getWayIdentity();
    var signed = edge.isBack() ? identity.backwardDirection() : identity.forwardDirection();
    if (signed != null && !signed.isBlank()) {
      String name = direction.name();
      String tag = signed.toUpperCase(Locale.ROOT);
      return tag.equals(name) || tag.equals(name + "BOUND") || tag.equals(name.substring(0, 1));
    }
    double component = switch (direction) {
      case EAST -> vector.x;
      case WEST -> -vector.x;
      case NORTH -> vector.y;
      case SOUTH -> -vector.y;
      default -> 0;
    };
    return component > .55 * Math.hypot(vector.x, vector.y);
  }

  private static boolean matchesName(OsmWayIdentity identity, String sourceName) {
    var name = normalize(sourceName);
    if (name.isEmpty()) {
      return false;
    }
    if (name.equals(normalize(identity.name()))) {
      return true;
    }
    if (identity.ref() != null) {
      for (String ref : identity.ref().split(";")) {
        if (name.equals(normalize(ref))) {
          return true;
        }
      }
    }
    return false;
  }

  private static String normalize(@Nullable String name) {
    if (name == null) {
      return "";
    }
    var text = DIRECTION_SUFFIX.matcher(name.strip().toUpperCase(Locale.ROOT)).replaceFirst("");
    var route = ROUTE.matcher(text);
    if (route.matches()) {
      var network = switch (route.group(1)) {
        case "INTERSTATE" -> "I";
        case "STATE ROUTE", "SR" -> "CA";
        default -> route.group(1);
      };
      return network + route.group(2);
    }
    return text;
  }

  private static boolean matchesFacility(OsmWayIdentity road, Facility facility) {
    String highway = road.highway();
    if (highway == null) {
      return false;
    }
    return switch (facility) {
      case SIDEWALK -> highway.equals("footway") && "sidewalk".equals(road.footway());
      case BIKE_LANE -> highway.equals("cycleway");
      case ROAD -> !List.of(
        "footway",
        "cycleway",
        "path",
        "steps",
        "pedestrian",
        "bridleway"
      ).contains(highway);
    };
  }

  public StreetTrafficSnapshot matchSpeeds(
    Map<String, PemsStation> stations,
    PemsSpeeds speeds,
    Duration maxAge
  ) {
    var footprints = stationFootprints(stations);
    Map<StreetEdge, List<SpeedSnapshot.Section>> sections = new HashMap<>();
    long start = speeds.observedAt().toEpochMilli();
    long end = speeds.observedAt().plus(maxAge).toEpochMilli();
    speeds.metersPerSecond().forEach((id, speed) -> {
      var matched = footprints.get(id);
      if (matched != null && Double.isFinite(speed) && speed > 0) {
        var notice = new StreetTrafficNotice(
          "Caltrans PeMS",
          id,
          "MEASURED_SPEED",
          "Measured freeway speed",
          speeds.observedAt()
        );
        sections
          .computeIfAbsent(matched.edge(), ignored -> new ArrayList<>())
          .add(new SpeedSnapshot.Section(matched.span(), speed, 0, start, end, notice));
      }
    });
    return sections.isEmpty() ? StreetTrafficSnapshot.NONE : new SpeedSnapshot(sections);
  }

  /** Detector geometry and neighboring stations change only when the station directory changes. */
  private synchronized Map<String, MatchedEdge> stationFootprints(
    Map<String, PemsStation> stations
  ) {
    if (stationDirectory.equals(stations)) {
      return stationFootprints;
    }
    Map<StreetEdge, List<Detector>> located = new HashMap<>();
    for (var station : stations.values()) {
      var hit = stationHit(station);
      if (hit != null) {
        located
          .computeIfAbsent(hit.road.edge, ignored -> new ArrayList<>())
          .add(new Detector(station.id(), hit.offset / hit.road.geometry.length));
      }
    }
    Map<String, MatchedEdge> footprints = new HashMap<>();
    located.forEach((edge, detectors) -> {
      detectors.sort(Comparator.comparingDouble(Detector::fraction));
      double reach = MAX_STATION_REACH_METERS / edge.getDistanceMeters();
      for (int i = 0; i < detectors.size(); i++) {
        var station = detectors.get(i);
        double previous = i == 0 ? 0 : (detectors.get(i - 1).fraction + station.fraction) / 2;
        double next =
          i == detectors.size() - 1 ? 1 : (detectors.get(i + 1).fraction + station.fraction) / 2;
        var span = new Span(
          Math.max(previous, station.fraction - reach),
          Math.min(next, station.fraction + reach)
        );
        if (span.size() > 0) {
          footprints.put(station.id(), new MatchedEdge(edge, span));
        }
      }
    });
    stationDirectory = Map.copyOf(stations);
    stationFootprints = Map.copyOf(footprints);
    return stationFootprints;
  }

  private record Detector(String id, double fraction) {}

  private @Nullable Hit stationHit(PemsStation station) {
    var coordinate = new Coordinate(station.longitude(), station.latitude());
    var point = RoadGeometry.project(coordinate);
    var candidates = roads(
      new Envelope(coordinate),
      identity ->
        ("motorway".equals(identity.highway()) || "trunk".equals(identity.highway())) &&
        matchesRouteNumber(identity.ref(), station.route())
    );
    List<Hit> hits = new ArrayList<>();
    double nearest = Double.POSITIVE_INFINITY;
    double nearestManagedLane = Double.POSITIVE_INFINITY;
    for (var candidate : candidates) {
      if (!candidate.edge.canTraverse(TraverseMode.CAR) || candidate.edge.isLink()) {
        continue;
      }
      var hit = project(candidate, point);
      if (hit.distance > MAX_DISTANCE_METERS) {
        continue;
      }
      nearest = Math.min(nearest, hit.distance);
      var identity = candidate.identity;
      if (
        "designated".equals(identity.hov()) ||
        "only".equals(identity.hov()) ||
        (identity.name() != null && MANAGED_LANES_NAME.matcher(identity.name()).find())
      ) {
        nearestManagedLane = Math.min(nearestManagedLane, hit.distance);
        continue;
      }
      if (
        agreesWithDirection(
          candidate.edge,
          station.direction(),
          candidate.geometry.tangent(hit.offset)
        )
      ) {
        hits.add(hit);
      }
    }
    var selected = uniqueHit(hits, null);
    return selected != null &&
      selected.distance <= nearest + AMBIGUITY_METERS &&
      selected.distance + AMBIGUITY_METERS < nearestManagedLane
      ? selected
      : null;
  }

  private static boolean matchesRouteNumber(@Nullable String ref, String routeNumber) {
    if (ref == null) {
      return false;
    }
    for (String part : ref.split(";")) {
      var route = ROUTE.matcher(part.strip().toUpperCase(Locale.ROOT));
      if (route.matches() && route.group(2).equals(routeNumber)) {
        return true;
      }
    }
    return false;
  }
}
