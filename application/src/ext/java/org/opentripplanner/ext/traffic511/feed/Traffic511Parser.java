package org.opentripplanner.ext.traffic511.feed;

import static org.opentripplanner.ext.traffic511.feed.TrafficEvent.Effect.ADVISORY;
import static org.opentripplanner.ext.traffic511.feed.TrafficEvent.Effect.DETOUR;
import static org.opentripplanner.ext.traffic511.feed.TrafficEvent.Effect.FULL_CLOSURE;
import static org.opentripplanner.ext.traffic511.feed.TrafficEvent.Effect.REPORTED_DELAY;

import java.io.InputStream;
import java.net.URI;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Lineal;
import org.opentripplanner.framework.json.ObjectMappers;
import org.opentripplanner.street.model.traffic.StreetTrafficNotice;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class Traffic511Parser {

  private static final ObjectMapper JSON = ObjectMappers.geoJson();
  private static final Pattern DELAY = Pattern.compile(
    "(\\d{1,3}) (minute|hour)s? delay",
    Pattern.CASE_INSENSITIVE
  );

  private Traffic511Parser() {}

  /** Only the validated numeric offset is carried over from a potentially credential-bearing URL. */
  public record EventPage(List<TrafficEvent> events, int nextOffset) {}

  public record WorkZoneFeed(Instant issuedAt, List<TrafficEvent> events) {}

  public static EventPage readEvents(InputStream data) {
    return readEvents(data, Instant.MAX);
  }

  public static EventPage readEvents(InputStream data, Instant openIncidentCutoff) {
    var root = JSON.readTree(data);
    var result = new ArrayList<TrafficEvent>();
    for (var event : requiredArray(root, "events")) {
      if (!event.path("status").asString("").equalsIgnoreCase("ACTIVE")) {
        continue;
      }
      var id = event.path("id").asString("");
      var geometry = event.path("+closure_geography");
      if (geometry.isMissingNode() || geometry.isNull()) {
        geometry = event.path("geography");
      }
      var shape = geometry(geometry);
      if (id.isBlank() || shape == null) {
        continue;
      }
      EventSchedule schedule;
      try {
        schedule = Open511Schedule.parse(event, openIncidentCutoff);
      } catch (DateTimeException | IllegalArgumentException e) {
        // A local schedule we cannot interpret cannot establish that a closure is active.
        continue;
      }
      var roads = event.path("roads");
      for (var road : roads) {
        var name = road.path("name").asString("");
        var direction = TrafficEvent.Direction.parse(road.path("direction").asString(""));
        var state = keyword(road, "state");
        var lane = keyword(road, "+lane_type");
        var status = keyword(road, "+lane_status");
        var advisory = DELAY.matcher(road.path("+road_advisory").asString("").strip());
        int delaySeconds = 0;
        if (advisory.matches()) {
          delaySeconds =
            Integer.parseInt(advisory.group(1)) *
            (advisory.group(2).equalsIgnoreCase("hour") ? 3600 : 60);
        }
        var effect = ADVISORY;
        if (
          shape instanceof Lineal &&
          roads.size() == 1 &&
          direction != TrafficEvent.Direction.UNKNOWN &&
          !name.isBlank()
        ) {
          if (
            state.equals("closed") &&
            (lane.isEmpty() || lane.equals("all lanes")) &&
            (status.isEmpty() ||
              List.of("closed", "blocked", "remain closed", "remains closed").contains(status))
          ) {
            effect = FULL_CLOSURE;
          } else if (delaySeconds > 0 && !state.equals("closed")) {
            effect = REPORTED_DELAY;
          }
        }
        var notice = new StreetTrafficNotice(
          "511 Traffic Events",
          id,
          effect.name(),
          event.path("headline").asString("Traffic event on " + name),
          updated(event, "updated", null)
        );
        result.add(
          new TrafficEvent(
            id,
            name,
            direction,
            shape,
            effect,
            TrafficEvent.Facility.ROAD,
            delaySeconds,
            schedule,
            notice
          )
        );
      }
    }
    return new EventPage(
      List.copyOf(result),
      nextOffset(root.path("pagination").path("next_url").asString(""))
    );
  }

  static int nextOffset(String link) {
    if (link.isBlank()) {
      return -1;
    }
    try {
      var uri = URI.create("https://api.511.org/traffic/events").resolve(link);
      if (
        !"https".equalsIgnoreCase(uri.getScheme()) ||
        !"api.511.org".equalsIgnoreCase(uri.getHost()) ||
        (uri.getPort() != -1 && uri.getPort() != 443) ||
        uri.getUserInfo() != null ||
        !uri.getPath().matches("(?i)/traffic/events/?") ||
        uri.getRawQuery() == null
      ) {
        throw new IllegalArgumentException();
      }
      for (var parameter : uri.getRawQuery().split("&")) {
        if (parameter.matches("offset=[0-9]+")) {
          return Integer.parseInt(parameter.substring("offset=".length()));
        }
      }
    } catch (IllegalArgumentException e) {
      // next_url can contain the token. Do not propagate URI parser exceptions.
    }
    throw new IllegalArgumentException("Invalid 511 pagination link");
  }

  public static List<TrafficEvent> readWorkZones(InputStream data) {
    return readWorkZoneFeed(data).events();
  }

  public static WorkZoneFeed readWorkZoneFeed(InputStream data) {
    var root = JSON.readTree(data);
    var info = root.has("feed_info") ? root.path("feed_info") : root.path("road_event_feed_info");
    var version = info.path("version").asString("");
    if (
      !root.path("type").asString("").equals("FeatureCollection") ||
      !(version.equals("4.0") || version.equals("4.1"))
    ) {
      throw new IllegalArgumentException("Unsupported WZDx feed");
    }
    var issued = Instant.parse(info.path("update_date").asString(""));
    var result = new ArrayList<TrafficEvent>();
    for (var feature : requiredArray(root, "features")) {
      var id = feature.path("id").asString("");
      var props = feature.path("properties");
      var core = props.path("core_details");
      var shape = geometry(feature.path("geometry"));
      var status = keyword(props, "event_status");
      if (
        id.isBlank() ||
        shape == null ||
        List.of("completed", "cancelled", "canceled").contains(status)
      ) {
        continue;
      }
      EventSchedule schedule;
      try {
        schedule = EventSchedule.interval(
          Instant.parse(props.path("start_date").asString("")),
          Instant.parse(props.path("end_date").asString(""))
        );
      } catch (DateTimeException | IllegalArgumentException e) {
        continue;
      }
      var names = core.path("road_names");
      var name = names.path(0).asString("");
      var direction = TrafficEvent.Direction.parse(core.path("direction").asString(""));
      var type = keyword(core, "event_type");
      var impact = keyword(props, "vehicle_impact");
      boolean confirmed =
        shape instanceof Lineal &&
        names.size() == 1 &&
        !name.isBlank() &&
        direction != TrafficEvent.Direction.UNKNOWN &&
        List.of("", "active", "pending", "planned").contains(status) &&
        List.of("work-zone", "special-event").contains(type);
      var effect = type.equals("detour") ? DETOUR : ADVISORY;
      if (confirmed && impact.equals("all-lanes-closed")) {
        effect = FULL_CLOSURE;
      }
      var summary = core.path("description").asString("Road event on " + name);
      var reportTime = updated(core, "update_date", issued);
      var notice = new StreetTrafficNotice("511 WZDx", id, effect.name(), summary, reportTime);
      result.add(
        new TrafficEvent(
          id,
          name,
          direction,
          shape,
          effect,
          TrafficEvent.Facility.ROAD,
          0,
          schedule,
          notice
        )
      );
      for (var lane : props.path("lanes")) {
        var facility = switch (keyword(lane, "type")) {
          case "sidewalk" -> TrafficEvent.Facility.SIDEWALK;
          case "bike-lane" -> TrafficEvent.Facility.BIKE_LANE;
          default -> TrafficEvent.Facility.ROAD;
        };
        if (facility != TrafficEvent.Facility.ROAD && keyword(lane, "status").equals("closed")) {
          var laneEffect = confirmed ? FULL_CLOSURE : ADVISORY;
          var laneNotice = new StreetTrafficNotice(
            "511 WZDx",
            id,
            laneEffect.name(),
            summary,
            reportTime
          );
          result.add(
            new TrafficEvent(
              id,
              name,
              direction,
              shape,
              laneEffect,
              facility,
              0,
              schedule,
              laneNotice
            )
          );
        }
      }
    }
    return new WorkZoneFeed(issued, List.copyOf(result));
  }

  private static @Nullable Instant updated(
    JsonNode node,
    String field,
    @Nullable Instant fallback
  ) {
    try {
      return Instant.parse(node.path(field).asString(""));
    } catch (DateTimeException e) {
      return fallback;
    }
  }

  private static JsonNode requiredArray(JsonNode root, String field) {
    var array = root.path(field);
    if (!array.isArray()) {
      throw new IllegalArgumentException("Missing traffic feed collection");
    }
    return array;
  }

  private static String keyword(JsonNode object, String field) {
    return object.path(field).asString("").strip().toLowerCase(Locale.ROOT);
  }

  private static @Nullable Geometry geometry(JsonNode node) {
    if (!node.isObject()) {
      return null;
    }
    try {
      Geometry shape = JSON.treeToValue(node, Geometry.class);
      if (shape == null || shape.isEmpty()) {
        return null;
      }
      for (var coord : shape.getCoordinates()) {
        if (!(Math.abs(coord.x) <= 180 && Math.abs(coord.y) <= 90)) {
          return null;
        }
      }
      return shape;
    } catch (JacksonException | IllegalArgumentException e) {
      return null;
    }
  }
}
