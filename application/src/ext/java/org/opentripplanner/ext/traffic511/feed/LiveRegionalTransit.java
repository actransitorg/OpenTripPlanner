package org.opentripplanner.ext.traffic511.feed;

import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Maintains complete live files independently and withdraws expired trip predictions. */
final class LiveRegionalTransit {

  private static final Logger LOG = LoggerFactory.getLogger(LiveRegionalTransit.class);
  private static final long MAX_AGE_SECONDS = 30 * 60;
  private final Path directory;
  private long lastTripIssue = Long.MIN_VALUE;
  private boolean hasPredictions;

  LiveRegionalTransit(Path directory) throws IOException {
    this.directory = directory.toFile().getCanonicalFile().toPath();
    for (var p = this.directory; p != null; p = p.getParent()) {
      if (Files.exists(p.resolve("captured-at.txt"))) {
        throw new IOException("Choose a live directory outside timestamped captures");
      }
    }
    Files.createDirectories(this.directory);
    var trips = this.directory.resolve(RegionalRealtime.TRIPS.filename);
    if (Files.exists(trips)) {
      try {
        var feed = RegionalRealtime.TRIPS.parse(Files.readAllBytes(trips));
        lastTripIssue = issuedAt(
          feed,
          Files.getLastModifiedTime(trips).toInstant().getEpochSecond()
        );
        hasPredictions = feed.getEntityCount() > 0;
      } catch (IOException e) {
        LOG.warn("Existing live TripUpdates could not be validated");
      }
    }
  }

  void refresh(Traffic511Capture capture) throws InterruptedException {
    var tasks = Executors.newScheduledThreadPool(RegionalRealtime.values().length + 1);
    try {
      tasks.scheduleWithFixedDelay(
        () -> {
          try {
            expire(Instant.now());
          } catch (IOException e) {
            LOG.warn("Could not withdraw expired TripUpdates");
          }
        },
        0,
        1,
        TimeUnit.SECONDS
      );
      for (var stream : RegionalRealtime.values()) {
        tasks.scheduleWithFixedDelay(
          () -> {
            try {
              var download = capture.downloadRealtime(stream);
              publish(stream, download, Instant.now());
            } catch (IOException e) {
              LOG.warn("Could not refresh {}: {}", stream.filename, e.getMessage());
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          },
          0,
          stream.interval.toSeconds(),
          TimeUnit.SECONDS
        );
      }
      new CountDownLatch(1).await();
    } finally {
      tasks.shutdownNow();
    }
  }

  synchronized void publish(
    RegionalRealtime stream,
    Traffic511Capture.RealtimeDownload download,
    Instant received
  ) throws IOException {
    var data = download.data();
    var feed = stream.parse(data);
    if (stream == RegionalRealtime.TRIPS) {
      var now = received.getEpochSecond();
      var issued = issuedAt(feed, Math.min(now, download.validatedAt().getEpochSecond()));
      if (issued <= now - MAX_AGE_SECONDS || feed.getHeader().getTimestamp() > now + 60) {
        throw new IOException("Stale or future RG TripUpdates");
      }
      if (issued < lastTripIssue) {
        throw new IOException("Out-of-order RG TripUpdates");
      }
      replace(stream.filename, data, Instant.ofEpochSecond(issued));
      lastTripIssue = issued;
      hasPredictions = feed.getEntityCount() > 0;
    } else {
      replace(stream.filename, data, received);
    }
  }

  synchronized void expire(Instant now) throws IOException {
    if (hasPredictions && now.getEpochSecond() >= lastTripIssue + MAX_AGE_SECONDS) {
      var empty = FeedMessage.newBuilder()
        .setHeader(
          FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(now.getEpochSecond())
        )
        .build();
      // Preserve the source cutoff across restart. A fresh provider update may predate this withdrawal.
      replace(
        RegionalRealtime.TRIPS.filename,
        empty.toByteArray(),
        Instant.ofEpochSecond(lastTripIssue)
      );
      hasPredictions = false;
    }
  }

  private static long issuedAt(FeedMessage feed, long validatedAt) {
    var header = feed.getHeader();
    return header.hasTimestamp() ? Math.min(header.getTimestamp(), validatedAt) : validatedAt;
  }

  private void replace(String filename, byte[] data, Instant modified) throws IOException {
    var temp = Files.createTempFile(directory, filename, ".tmp");
    try {
      Files.write(temp, data);
      Files.setLastModifiedTime(temp, FileTime.from(modified));
      Files.move(
        temp,
        directory.resolve(filename),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      );
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
