package org.opentripplanner.ext.traffic511.feed;

import java.util.Locale;
import org.locationtech.jts.geom.Geometry;
import org.opentripplanner.street.model.traffic.StreetTrafficNotice;

/** A source-reported impact on one named road or facility, before matching to the street graph. */
public record TrafficEvent(
  String id,
  String roadName,
  Direction direction,
  Geometry geometry,
  Effect effect,
  Facility facility,
  int delaySeconds,
  EventSchedule schedule,
  StreetTrafficNotice notice
) {
  public enum Effect {
    FULL_CLOSURE,
    REPORTED_DELAY,
    ADVISORY,
    DETOUR,
  }

  public enum Facility {
    ROAD,
    SIDEWALK,
    BIKE_LANE,
  }

  public enum Direction {
    NORTH,
    SOUTH,
    EAST,
    WEST,
    BOTH,
    UNKNOWN;

    static Direction parse(String value) {
      return switch (value.strip().toUpperCase(Locale.ROOT)) {
        case "N", "NORTH", "NORTHBOUND" -> NORTH;
        case "S", "SOUTH", "SOUTHBOUND" -> SOUTH;
        case "E", "EAST", "EASTBOUND" -> EAST;
        case "W", "WEST", "WESTBOUND" -> WEST;
        case
          "BOTH",
          "NORTHBOUND AND SOUTHBOUND",
          "EASTBOUND AND WESTBOUND",
          "SOUTHBOUND AND NORTHBOUND",
          "WESTBOUND AND EASTBOUND" -> BOTH;
        default -> UNKNOWN;
      };
    }
  }
}
