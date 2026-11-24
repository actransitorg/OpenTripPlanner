package org.opentripplanner.ext.traffic511.feed;

/** District 4 mainline detector. The absolute postmile is in miles on the signed freeway. */
public record PemsStation(
  String id,
  String route,
  TrafficEvent.Direction direction,
  double longitude,
  double latitude,
  double absolutePostmile
) {}
