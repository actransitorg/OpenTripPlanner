package org.opentripplanner.ext.traffic511.feed;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import tools.jackson.databind.JsonNode;

final class Open511Schedule {

  static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");

  private Open511Schedule() {}

  static EventSchedule parse(JsonNode event, Instant openIncidentCutoff) {
    var zone = ZoneId.of(event.path("timezone").asString(PACIFIC.getId()));
    boolean incident = "INCIDENT".equalsIgnoreCase(event.path("event_type").asString(""));
    Instant horizon = incident ? openIncidentCutoff : Instant.MAX;
    var schedule = event.path("schedule");
    var rules = new ArrayList<EventSchedule>();
    if (schedule.isObject()) {
      readSchedule(schedule, zone, horizon, rules);
    } else {
      // Older payloads contain a schedules array of date rules.
      for (var entry : event.path("schedules")) {
        readSchedule(entry, zone, horizon, rules);
      }
    }
    if (rules.isEmpty()) {
      throw new IllegalArgumentException("Missing event schedule");
    }
    if (rules.size() == 1) {
      return rules.getFirst();
    }
    return (start, end, visitor) ->
      rules.stream().anyMatch(rule -> rule.visitIntervals(start, end, visitor));
  }

  private static void readSchedule(
    JsonNode node,
    ZoneId zone,
    Instant openEnd,
    List<EventSchedule> rules
  ) {
    if (node.has("intervals")) {
      if (
        !node.path("intervals").isArray() ||
        node.has("recurring_schedules") ||
        node.has("exceptions")
      ) {
        throw new IllegalArgumentException("Invalid interval schedule");
      }
      for (var item : node.path("intervals")) {
        var bounds = item.asString("").split("/", -1);
        if (bounds.length != 2) {
          throw new IllegalArgumentException("Invalid event interval");
        }
        rules.add(
          EventSchedule.interval(
            parseTime(bounds[0], zone),
            bounds[1].isEmpty() ? openEnd : parseTime(bounds[1], zone)
          )
        );
      }
      return;
    }
    var repeats = new ArrayList<DailyRule>();
    if (node.has("start_date")) {
      repeats.add(DailyRule.parse(node));
    } else {
      for (var item : node.path("recurring_schedules")) {
        repeats.add(DailyRule.parse(item));
      }
    }
    if (repeats.isEmpty()) {
      throw new IllegalArgumentException("Missing recurring schedule");
    }
    var exceptions = new HashMap<LocalDate, List<TimeRange>>();
    for (var item : node.path("exceptions")) {
      var parts = item.asString("").strip().split("\\s+");
      var date = LocalDate.parse(parts[0]);
      var ranges = new ArrayList<TimeRange>();
      for (int i = 1; i < parts.length; ++i) {
        var bounds = parts[i].split("-", -1);
        if (bounds.length != 2) {
          throw new IllegalArgumentException("Invalid event exception");
        }
        var range = new TimeRange(LocalTime.parse(bounds[0]), LocalTime.parse(bounds[1]));
        if (!range.end().isAfter(range.start())) {
          throw new IllegalArgumentException("Invalid event exception range");
        }
        ranges.add(range);
      }
      exceptions.put(date, List.copyOf(ranges));
    }
    rules.add((start, end, visitor) ->
      visitRecurring(start, end, zone, repeats, exceptions, openEnd, visitor)
    );
  }

  private static Instant parseTime(String value, ZoneId zone) {
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (DateTimeParseException e) {
      return LocalDateTime.parse(value).atZone(zone).toInstant();
    }
  }

  private static boolean visitRecurring(
    Instant start,
    Instant end,
    ZoneId zone,
    List<DailyRule> rules,
    Map<LocalDate, List<TimeRange>> exceptions,
    Instant openEnd,
    BiPredicate<Instant, Instant> visitor
  ) {
    // Evaluate each local calendar date separately. A date exception replaces even the portion
    // of an overnight recurring interval that would otherwise spill into that date.
    var last = end.atZone(zone).toLocalDate();
    for (var day = start.atZone(zone).toLocalDate(); !day.isAfter(last); day = day.plusDays(1)) {
      var midnight = day.atStartOfDay(zone).toInstant();
      var nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant();
      var clippedStart = start.isAfter(midnight) ? start : midnight;
      var clippedEnd = end.isBefore(nextMidnight) ? end : nextMidnight;
      if (!start.equals(end) && !clippedStart.isBefore(clippedEnd)) {
        continue;
      }
      var override = exceptions.get(day);
      if (override != null) {
        for (var range : override) {
          if (range.visit(day, zone, clippedStart, clippedEnd, visitor)) {
            return true;
          }
        }
      } else {
        for (var rule : rules) {
          var ruleEnd =
            rule.last().equals(LocalDate.MAX) && openEnd.isBefore(clippedEnd)
              ? openEnd
              : clippedEnd;
          if (
            ruleEnd.isBefore(clippedStart) ||
            (openEnd.equals(clippedStart) && rule.last().equals(LocalDate.MAX))
          ) {
            continue;
          }
          if (
            rule.visit(day, zone, clippedStart, ruleEnd, visitor) ||
            (!exceptions.containsKey(day.minusDays(1)) &&
              rule.visit(day.minusDays(1), zone, clippedStart, ruleEnd, visitor))
          ) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private record TimeRange(LocalTime start, LocalTime end) {
    boolean visit(
      LocalDate date,
      ZoneId zone,
      Instant from,
      Instant until,
      BiPredicate<Instant, Instant> visitor
    ) {
      var lastDate = end.isAfter(start) ? date : date.plusDays(1);
      var begin = date.atTime(start).atZone(zone).toInstant();
      var finish = lastDate.atTime(end).atZone(zone).withLaterOffsetAtOverlap().toInstant();
      // Spring-forward gaps can collapse a local interval to zero elapsed time.
      return (
        finish.isAfter(begin) &&
        EventSchedule.interval(begin, finish).visitIntervals(from, until, visitor)
      );
    }
  }

  private record DailyRule(LocalDate first, LocalDate last, Set<DayOfWeek> days, TimeRange range) {
    static DailyRule parse(JsonNode n) {
      var first = LocalDate.parse(n.path("start_date").asString(""));
      var last = n.has("end_date")
        ? LocalDate.parse(n.path("end_date").asString(""))
        : LocalDate.MAX;
      if (last.isBefore(first) || n.has("daily_start_time") != n.has("daily_end_time")) {
        throw new IllegalArgumentException("Invalid recurring dates or times");
      }
      var weekdays = EnumSet.noneOf(DayOfWeek.class);
      if (n.has("days")) {
        if (!n.path("days").isArray()) {
          throw new IllegalArgumentException("Invalid days");
        }
        for (var day : n.path("days")) {
          weekdays.add(DayOfWeek.of(day.asInt()));
        }
      } else {
        weekdays = EnumSet.allOf(DayOfWeek.class);
      }
      var range = new TimeRange(
        LocalTime.parse(n.path("daily_start_time").asString("00:00")),
        LocalTime.parse(n.path("daily_end_time").asString("00:00"))
      );
      return new DailyRule(first, last, weekdays, range);
    }

    boolean visit(
      LocalDate day,
      ZoneId zone,
      Instant from,
      Instant until,
      BiPredicate<Instant, Instant> visitor
    ) {
      return (
        !day.isBefore(first) &&
        !day.isAfter(last) &&
        days.contains(day.getDayOfWeek()) &&
        range.visit(day, zone, from, until, visitor)
      );
    }
  }
}
