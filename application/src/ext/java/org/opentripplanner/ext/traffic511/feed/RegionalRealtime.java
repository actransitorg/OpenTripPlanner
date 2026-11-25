package org.opentripplanner.ext.traffic511.feed;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import java.io.IOException;
import java.time.Duration;
import java.util.function.Predicate;

enum RegionalRealtime {
  TRIPS("tripupdates.pb", "trip-updates", 3, FeedEntity::hasTripUpdate),
  VEHICLES("vehiclepositions.pb", "vehicle-positions", 10, FeedEntity::hasVehicle),
  ALERTS("alerts.pb", "service-alerts", 10, FeedEntity::hasAlert);

  final String filename;
  final String route;
  final Duration interval;
  private final Predicate<FeedEntity> accepts;

  RegionalRealtime(String filename, String route, int minutes, Predicate<FeedEntity> accepts) {
    this.filename = filename;
    this.route = "gtfs-rt/" + route + "/rg";
    this.interval = Duration.ofMinutes(minutes);
    this.accepts = accepts;
  }

  FeedMessage parse(byte[] data) throws IOException {
    var feed = FeedMessage.parseFrom(data);
    if (
      feed.getHeader().getGtfsRealtimeVersion().isBlank() ||
      feed.getHeader().getIncrementality() != FeedHeader.Incrementality.FULL_DATASET ||
      feed
        .getEntityList()
        .stream()
        .anyMatch(e -> e.getIsDeleted() || !accepts.test(e))
    ) {
      throw new IOException("Expected a complete regional GTFS-RT dataset in " + filename);
    }
    return feed;
  }
}
