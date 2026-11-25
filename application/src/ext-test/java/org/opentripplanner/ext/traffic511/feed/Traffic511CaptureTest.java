package org.opentripplanner.ext.traffic511.feed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Traffic511CaptureTest {

  private static final String KEY = "fixture-credential";
  private static final byte[] TOKEN = KEY.getBytes(StandardCharsets.UTF_8);
  private static final URI BASE = URI.create("https://cache.example.invalid/test/511/");
  private static final List<String> TABLES = List.of(
    "agency.txt",
    "routes.txt",
    "trips.txt",
    "stop_times.txt"
  );

  @Test
  void publishesARegionalSnapshotOnlyAfterAllFeedsArrive(@TempDir Path dir) throws Exception {
    var feeds = realtimeFixtures();
    feeds.put("gtfs-static/rg", Files.readAllBytes(zip(dir, TABLES, new byte[0])));
    var alerts = feeds.remove("gtfs-rt/service-alerts/rg");
    var capture = fixtureCapture(feeds, Map.of());
    var parent = dir.resolve("captures");
    var target = parent.resolve("regional");
    assertThrows(IOException.class, () -> capture.snapshot(target, true));
    try (var entries = Files.list(parent)) {
      assertEquals(0, entries.count());
    }

    feeds.put("gtfs-rt/service-alerts/rg", alerts);
    capture.snapshot(target, true);
    try (var entries = Files.list(target)) {
      assertEquals(
        List.of(
          "RG.gtfs.zip",
          "alerts.pb",
          "captured-at.txt",
          "tripupdates.pb",
          "vehiclepositions.pb"
        ),
        entries
          .map(p -> p.getFileName().toString())
          .sorted()
          .toList()
      );
    }
    assertArrayEquals(alerts, Files.readAllBytes(target.resolve("alerts.pb")));
    assertThrows(IOException.class, () -> capture.snapshot(target, true));
  }

  @Test
  void archivesCompleteCachedRoadFeedsAndPublicPemsData(@TempDir Path dir) throws Exception {
    var feeds = realtimeFixtures();
    feeds.put(
      "traffic/events",
      """
      {"events":[],"pagination":{"next_url":"/traffic/events?offset=20"}}
      """.getBytes(StandardCharsets.UTF_8)
    );
    var zones = """
    {"type":"FeatureCollection","feed_info":{"version":"4.1","update_date":"2020-01-01T00:00:00Z"},"features":[]}
    """.getBytes(StandardCharsets.UTF_8);
    feeds.put("traffic/work-zones", zones);
    var stations = "<pems><district id=\"4\"/></pems>";
    var speeds = "01/01/2020 08:00:00\n400000,266,.0579,64.2,110.39,1.7,64.2,,.02,40,100\n";
    var publicFeeds = new HashMap<URI, String>();
    var capture = fixtureCapture(feeds, publicFeeds);
    var target = dir.resolve("roads");
    assertThrows(IOException.class, () -> capture.snapshot(target, false));
    assertFalse(Files.exists(target));

    feeds.put(
      "traffic/events",
      """
      {"events":[],"meta":{"request":"fixture-credential"}}
      """.getBytes(StandardCharsets.UTF_8)
    );
    feeds.put(
      "traffic/work-zones",
      """
      {"type":"FeatureCollection","features":[]}
      """.getBytes(StandardCharsets.UTF_8)
    );
    assertThrows(IOException.class, () -> capture.snapshot(target, false));
    assertFalse(Files.exists(target));
    feeds.put("traffic/work-zones", zones);
    publicFeeds.put(PemsClient.STATIONS, "<html>Maintenance</html>");
    assertThrows(IOException.class, () -> capture.snapshot(target, false));
    assertFalse(Files.exists(target));
    publicFeeds.put(PemsClient.STATIONS, stations);
    publicFeeds.put(PemsClient.SPEEDS, "<html>Maintenance</html>");
    assertThrows(IOException.class, () -> capture.snapshot(target, false));
    assertFalse(Files.exists(target));
    publicFeeds.put(PemsClient.SPEEDS, speeds);
    capture.snapshot(target, false);
    assertEquals("{\"events\":[]}", Files.readString(target.resolve("events.json")));
    assertArrayEquals(zones, Files.readAllBytes(target.resolve("work-zones.json")));
    assertEquals(stations, Files.readString(target.resolve("stations.xml")));
    assertEquals(speeds, Files.readString(target.resolve("speeds.txt")));
    assertFalse(Files.exists(target.resolve("RG.gtfs.zip")));
  }

  private static Map<String, byte[]> realtimeFixtures() {
    var feed = FeedMessage.newBuilder()
      .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0"))
      .build()
      .toByteArray();
    return new HashMap<>(
      Map.of(
        "gtfs-rt/trip-updates/rg",
        feed,
        "gtfs-rt/vehicle-positions/rg",
        feed,
        "gtfs-rt/service-alerts/rg",
        feed
      )
    );
  }

  private static Traffic511Capture fixtureCapture(
    Map<String, byte[]> feeds,
    Map<URI, String> publicFeeds
  ) throws IOException {
    var transport = new FeedHttp(Duration.ofSeconds(1)) {
      @Override
      <T> T download(URI uri, String feed, @Nullable String key, CachedDecoder<T> decoder)
        throws IOException {
        byte[] payload;
        if (uri.getHost().equals(BASE.getHost())) {
          assertEquals(KEY, key);
          payload = feeds.get(BASE.relativize(uri).toString());
          if (payload == null) {
            throw new IOException("Unavailable fixture: " + feed);
          }
        } else {
          assertNull(key);
          assertTrue(publicFeeds.containsKey(uri), "Unexpected public request " + uri);
          payload = publicFeeds.get(uri).getBytes(StandardCharsets.UTF_8);
        }
        return decoder.read(new ByteArrayInputStream(payload), Instant.now());
      }
    };
    return new Traffic511Capture(new Traffic511Client(BASE, () -> KEY, transport), transport);
  }

  @Test
  void stripsArchiveMetadataAndPreservesTables(@TempDir Path dir) throws IOException {
    var source = zip(dir, TABLES, new byte[0]);
    var output = dir.resolve("published.zip");
    Traffic511Capture.copyGtfs(source, output, TOKEN);
    try (var archive = new ZipFile(output.toFile())) {
      assertEquals(TABLES.size(), archive.size());
      for (var name : TABLES) {
        try (var entry = archive.getInputStream(archive.getEntry(name))) {
          assertEquals(name, new String(entry.readAllBytes(), StandardCharsets.UTF_8));
        }
      }
    }
    assertFalse(new String(Files.readAllBytes(output), StandardCharsets.ISO_8859_1).contains(KEY));
  }

  @Test
  void rejectsIncompleteGtfsAndEmbeddedCredentials(@TempDir Path dir) throws IOException {
    var path = dir.resolve("published.zip");
    var incomplete = zip(dir, List.of("routes.txt"), new byte[0]);
    assertThrows(IOException.class, () -> Traffic511Capture.copyGtfs(incomplete, path, TOKEN));
    var contents = new byte[64 * 1024 + 2 * TOKEN.length];
    Arrays.fill(contents, (byte) 'a');
    System.arraycopy(TOKEN, 0, contents, 64 * 1024, TOKEN.length);
    var unsafe = zip(dir, TABLES, contents);
    assertThrows(IOException.class, () -> Traffic511Capture.copyGtfs(unsafe, path, TOKEN));
  }

  private static Path zip(Path dir, List<String> names, byte[] contents) throws IOException {
    var source = Files.createTempFile(dir, "source", ".zip");
    try (var out = new ZipOutputStream(Files.newOutputStream(source))) {
      out.setComment(KEY);
      for (var name : names) {
        var entry = new ZipEntry(name);
        entry.setComment(KEY);
        out.putNextEntry(entry);
        out.write(name.getBytes(StandardCharsets.UTF_8));
        out.write(contents);
        out.closeEntry();
      }
    }
    return source;
  }
}
