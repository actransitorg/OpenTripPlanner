package org.opentripplanner.ext.traffic511.feed;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.opentripplanner.framework.json.ObjectMappers;

/** Standalone entry point in the OTP shaded JAR for preparing and replaying regional feeds. */
public final class Traffic511Capture {

  private static final Duration TIMEOUT = Duration.ofSeconds(45);
  private static final String USAGE =
    "Traffic511Capture [--regional-transit | --refresh-transit] DIRECTORY";
  private final Traffic511Client cache;
  private final FeedHttp pems;
  private final byte[] credential;

  Traffic511Capture(Traffic511Client cache, FeedHttp pems) throws IOException {
    this.cache = cache;
    this.pems = pems;
    credential = cache.key().getBytes(StandardCharsets.UTF_8);
  }

  public static void main(String[] args) throws InterruptedException {
    try {
      var options = Options.parse(args);
      if (options.help) {
        System.out.println(USAGE);
        return;
      }
      var directory = Path.of(options.directories.getFirst()).toAbsolutePath();
      var capture = new Traffic511Capture(
        new Traffic511Client("ACTRANSIT_CACHE_API_KEY", TIMEOUT),
        new FeedHttp(TIMEOUT)
      );
      if (options.refresh) {
        new LiveRegionalTransit(directory).refresh(capture);
      } else {
        capture.snapshot(directory, options.regional);
      }
    } catch (IOException | IllegalArgumentException | ParameterException e) {
      System.err.println(e.getMessage());
      System.exit(1);
    }
  }

  static final class Options {

    @Parameter(names = { "-h", "--help" }, help = true)
    boolean help;

    @Parameter(names = "--regional-transit")
    boolean regional;

    @Parameter(names = "--refresh-transit")
    boolean refresh;

    @Parameter(description = "DIRECTORY")
    List<String> directories;

    static Options parse(String... args) {
      var options = new Options();
      JCommander.newBuilder().addObject(options).expandAtSign(false).build().parse(args);
      if (options.help) {
        return options;
      }
      if (
        options.directories == null ||
        options.directories.size() != 1 ||
        (options.regional && options.refresh)
      ) {
        throw new ParameterException(USAGE);
      }
      // JCommander treats an unrecognized option as a positional argument.
      boolean afterSeparator = args.length >= 2 && "--".equals(args[args.length - 2]);
      if (options.directories.getFirst().startsWith("-") && !afterSeparator) {
        throw new ParameterException(USAGE);
      }
      return options;
    }
  }

  byte[] download(String route) throws IOException, InterruptedException {
    return cache.readFeed(route, input -> checked(input.readAllBytes()));
  }

  record RealtimeDownload(byte[] data, Instant validatedAt) {}

  RealtimeDownload downloadRealtime(RegionalRealtime stream)
    throws IOException, InterruptedException {
    return cache.readCachedFeed(stream.route, (input, validatedAt) ->
      new RealtimeDownload(checked(input.readAllBytes()), validatedAt)
    );
  }

  private byte[] checked(byte[] data) throws IOException {
    if (data.length == 0 || containsCredential(data, data.length, credential)) {
      throw new IOException("Empty feed or credential in response");
    }
    return data;
  }

  private static boolean containsCredential(byte[] data, int length, byte[] credential) {
    for (int i = 0; i <= length - credential.length; i++) {
      if (
        data[i] == credential[0] &&
        Arrays.equals(data, i, i + credential.length, credential, 0, credential.length)
      ) {
        return true;
      }
    }
    return false;
  }

  void snapshot(Path directory, boolean regional) throws IOException, InterruptedException {
    if (Files.exists(directory)) {
      throw new IOException("Choose a new destination directory: " + directory);
    }
    Files.createDirectories(directory.getParent());
    var staging = Files.createTempDirectory(directory.getParent(), ".511-capture-");
    try {
      if (regional) {
        var raw = staging.resolve("download.zip");
        cache.readFeed("gtfs-static/rg", input -> Files.copy(input, raw));
        copyGtfs(raw, staging.resolve("RG.gtfs.zip"), credential);
        Files.delete(raw);
      } else {
        captureRoads(staging);
      }
      for (var stream : RegionalRealtime.values()) {
        var data = download(stream.route);
        stream.parse(data);
        Files.write(staging.resolve(stream.filename), data);
      }
      Files.writeString(staging.resolve("captured-at.txt"), Instant.now() + "\n");
      Files.move(staging, directory);
    } finally {
      if (Files.exists(staging)) {
        try (var files = Files.newDirectoryStream(staging)) {
          for (var file : files) {
            Files.delete(file);
          }
        }
        Files.delete(staging);
      }
    }
    System.out.println("Captured feeds to " + directory);
  }

  private void captureRoads(Path staging) throws IOException, InterruptedException {
    var json = ObjectMappers.ignoringExtraFields();
    var events = cache.readFeed("traffic/events", input -> {
      var page = json.readTree(input);
      if (
        !page.path("events").isArray() ||
        !page.path("pagination").path("next_url").asString("").isBlank()
      ) {
        throw new IOException("Expected complete cached Events");
      }
      // Only archive the events. Provider pagination metadata can contain an upstream key.
      var clean = json.createObjectNode();
      clean.set("events", page.get("events"));
      return validated(json.writeValueAsBytes(clean), Traffic511Parser::readEvents);
    });
    Files.write(staging.resolve("events.json"), events);
    var zones = cache.readFeed("traffic/work-zones", input ->
      validated(input.readAllBytes(), Traffic511Parser::readWorkZoneFeed)
    );
    Files.write(staging.resolve("work-zones.json"), zones);
    savePems(PemsClient.STATIONS, staging.resolve("stations.xml"), PemsParser::readStations);
    savePems(PemsClient.SPEEDS, staging.resolve("speeds.txt"), input ->
      PemsParser.readSpeeds(input, Instant.now())
    );
  }

  private byte[] validated(byte[] data, FeedHttp.Decoder<?> parser) throws IOException {
    checked(data);
    parser.read(new ByteArrayInputStream(data));
    return data;
  }

  private void savePems(URI uri, Path file, FeedHttp.Decoder<?> parser)
    throws IOException, InterruptedException {
    pems.read(uri, "Caltrans PeMS", input ->
      Files.write(file, validated(input.readAllBytes(), parser))
    );
  }

  /** Rebuild the ZIP without upstream request URLs in ZIP comments or extra fields. */
  static void copyGtfs(Path source, Path target, byte[] credential) throws IOException {
    var required = new HashSet<>(
      List.of("agency.txt", "routes.txt", "trips.txt", "stop_times.txt")
    );
    try (
      var archive = new ZipFile(source.toFile());
      var out = new ZipOutputStream(Files.newOutputStream(target))
    ) {
      var entries = archive.entries();
      var buffer = new byte[64 * 1024 + credential.length];
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        var name = entry.getName();
        var nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (containsCredential(nameBytes, nameBytes.length, credential)) {
          throw new IOException("Credential found in GTFS entry name");
        }
        required.remove(name);
        out.putNextEntry(new ZipEntry(name));
        try (InputStream input = archive.getInputStream(entry)) {
          var checksum = new CRC32();
          int tail = 0;
          int read;
          while ((read = input.read(buffer, tail, buffer.length - tail)) != -1) {
            int length = tail + read;
            if (containsCredential(buffer, length, credential)) {
              throw new IOException("Credential found in GTFS contents");
            }
            checksum.update(buffer, tail, read);
            out.write(buffer, tail, read);
            tail = Math.min(length, credential.length - 1);
            System.arraycopy(buffer, length - tail, buffer, 0, tail);
          }
          if (checksum.getValue() != entry.getCrc()) {
            throw new IOException("Corrupt regional GTFS entry");
          }
        }
        out.closeEntry();
      }
      if (!required.isEmpty()) {
        throw new IOException("Regional GTFS is missing tables: " + required);
      }
    }
  }
}
