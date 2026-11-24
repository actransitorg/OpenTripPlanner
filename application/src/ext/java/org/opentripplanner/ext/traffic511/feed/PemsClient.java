package org.opentripplanner.ext.traffic511.feed;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public final class PemsClient {

  private static final URI STATIONS = URI.create("https://pems.dot.ca.gov/feeds/vds_config.xml");
  private static final URI SPEEDS = URI.create(
    "https://pems.dot.ca.gov/feeds/D4/Data/5min/5minagg_latest.txt"
  );
  private final FeedHttp http;

  public PemsClient(Duration timeout) {
    http = new FeedHttp(timeout);
  }

  public Map<String, PemsStation> fetchStations() throws IOException, InterruptedException {
    return http.read(STATIONS, "PeMS station directory", PemsParser::readStations);
  }

  public PemsSpeeds fetchSpeeds() throws IOException, InterruptedException {
    return http.read(SPEEDS, "PeMS District 4 speeds", input ->
      PemsParser.readSpeeds(input, Instant.now())
    );
  }
}
