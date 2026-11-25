package org.opentripplanner.ext.traffic511.feed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveRegionalTransitTest {

  private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");

  @Test
  void preservesGoodDataUntilExpiryAndRecovers(@TempDir Path dir) throws IOException {
    var files = new LiveRegionalTransit(dir);
    var stream = RegionalRealtime.TRIPS;
    var path = dir.resolve(stream.filename);
    byte[] good = trips(NOW);
    files.publish(stream, download(good, NOW), NOW);
    for (var invalid : new byte[][] {
      { 10 },
      trips(NOW.minusSeconds(10)),
      trips(NOW.minusSeconds(1800)),
      trips(NOW.plusSeconds(61)),
    }) {
      assertThrows(IOException.class, () -> files.publish(stream, download(invalid, NOW), NOW));
      assertArrayEquals(good, Files.readAllBytes(path));
    }
    assertThrows(IOException.class, () ->
      files.publish(RegionalRealtime.ALERTS, download(good, NOW), NOW)
    );
    assertFalse(Files.exists(dir.resolve(RegionalRealtime.ALERTS.filename)));
    Files.setLastModifiedTime(path, FileTime.from(NOW));
    var restarted = new LiveRegionalTransit(dir);
    restarted.expire(NOW.plusSeconds(1799));
    assertArrayEquals(good, Files.readAllBytes(path));
    restarted.expire(NOW.plusSeconds(1800));
    assertEquals(0, stream.parse(Files.readAllBytes(path)).getEntityCount());
    var later = trips(NOW.plusSeconds(1801));
    restarted.publish(stream, download(later, NOW.plusSeconds(1801)), NOW.plusSeconds(1801));
    assertArrayEquals(later, Files.readAllBytes(path));
  }

  @Test
  void restartsFromReceiptTimeWhenEarlierThanSource(@TempDir Path dir) throws IOException {
    var path = dir.resolve(RegionalRealtime.TRIPS.filename);
    Files.write(path, trips(NOW));
    Files.setLastModifiedTime(path, FileTime.from(NOW.minusSeconds(1700)));
    new LiveRegionalTransit(dir).expire(NOW.plusSeconds(100));
    assertEquals(0, RegionalRealtime.TRIPS.parse(Files.readAllBytes(path)).getEntityCount());
  }

  @Test
  void repeatedCacheReadsOfUndatedTripsExpireAtValidationTime(@TempDir Path dir)
    throws IOException {
    var stream = RegionalRealtime.TRIPS;
    var feed = FeedMessage.parseFrom(trips(NOW)).toBuilder();
    feed.getHeaderBuilder().clearTimestamp();
    var cached = download(feed.build().toByteArray(), NOW);
    var files = new LiveRegionalTransit(dir);
    files.publish(stream, cached, NOW.plusSeconds(900));
    files.publish(stream, cached, NOW.plusSeconds(1799));
    var restarted = new LiveRegionalTransit(dir);
    var expiry = NOW.plusSeconds(1800);
    assertThrows(IOException.class, () -> restarted.publish(stream, cached, expiry));
    restarted.expire(expiry);
    assertEquals(
      0,
      stream.parse(Files.readAllBytes(dir.resolve(stream.filename))).getEntityCount()
    );
    // The expiry notice is local. A still-fresh provider update issued before it can recover.
    var recovery = trips(expiry.minusSeconds(60));
    new LiveRegionalTransit(dir).publish(stream, download(recovery, expiry), expiry);
    assertArrayEquals(recovery, Files.readAllBytes(dir.resolve(stream.filename)));
  }

  @Test
  void emptyProviderUpdateSupersedesOlderPredictionsAcrossRestart(@TempDir Path dir)
    throws IOException {
    var stream = RegionalRealtime.TRIPS;
    var cleared = FeedMessage.parseFrom(trips(NOW)).toBuilder().clearEntity().build().toByteArray();
    var files = new LiveRegionalTransit(dir);
    files.publish(stream, download(cleared, NOW), NOW);
    var old = download(trips(NOW.minusSeconds(1)), NOW);
    for (var live : List.of(files, new LiveRegionalTransit(dir))) {
      assertThrows(IOException.class, () -> live.publish(stream, old, NOW));
      assertArrayEquals(cleared, Files.readAllBytes(dir.resolve(stream.filename)));
    }
  }

  @Test
  void protectsCapturesAndDoesNotCreatePredictionsBeforeReceivingTrips(@TempDir Path dir)
    throws IOException {
    new LiveRegionalTransit(dir).expire(NOW);
    assertFalse(Files.exists(dir.resolve("tripupdates.pb")));
    Files.writeString(dir.resolve("captured-at.txt"), NOW.toString());
    assertThrows(IOException.class, () -> new LiveRegionalTransit(dir.resolve("nested")));
    assertFalse(Files.exists(dir.resolve("nested")));
  }

  private static byte[] trips(Instant issued) {
    return FeedMessage.newBuilder()
      .setHeader(
        FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(issued.getEpochSecond())
      )
      .addEntity(
        FeedEntity.newBuilder()
          .setId("test")
          .setTripUpdate(
            TripUpdate.newBuilder().setTrip(TripDescriptor.newBuilder().setTripId("AC:test"))
          )
      )
      .build()
      .toByteArray();
  }

  private static Traffic511Capture.RealtimeDownload download(byte[] data, Instant validatedAt) {
    return new Traffic511Capture.RealtimeDownload(data, validatedAt);
  }
}
