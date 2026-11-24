package org.opentripplanner.ext.traffic511.feed;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class Traffic511Client {

  private static final String HOST = "https://api.511.org/traffic/";
  // Leave the rest of the default 60 requests per token per hour for regional GTFS-RT and other
  // consumers. Count every attempted page, including failures. One token should use one env name.
  private static final int REQUESTS_PER_HOUR = 24;
  private static final ConcurrentHashMap<String, RollingBudget> BUDGETS = new ConcurrentHashMap<>();
  private final String apiKeyEnvironmentVariable;
  private final FeedHttp http;
  private final RollingBudget budget;

  public Traffic511Client(String apiKeyEnvironmentVariable, Duration timeout) {
    this.apiKeyEnvironmentVariable = apiKeyEnvironmentVariable;
    this.http = new FeedHttp(timeout);
    this.budget = BUDGETS.computeIfAbsent(apiKeyEnvironmentVariable, ignored ->
      new RollingBudget()
    );
  }

  public List<TrafficEvent> fetchEvents(Duration maxAge) throws IOException, InterruptedException {
    var events = new ArrayList<TrafficEvent>();
    var openIncidentCutoff = Instant.now().plus(maxAge);
    int offset = 0;
    do {
      var page = http.read(
        endpoint("events?format=json&status=ACTIVE&limit=500&offset=" + offset),
        "511 Events",
        input -> Traffic511Parser.readEvents(input, openIncidentCutoff)
      );
      events.addAll(page.events());
      if (page.nextOffset() != -1 && page.nextOffset() <= offset) {
        throw new IOException("511 event pagination failed to advance");
      }
      offset = page.nextOffset();
    } while (offset != -1);
    return List.copyOf(events);
  }

  public Traffic511Parser.WorkZoneFeed fetchWorkZoneFeed()
    throws IOException, InterruptedException {
    return http.read(
      endpoint("wzdx?includeAllDefinedEnums=true&allActiveAndFutureEvents=true"),
      "511 WZDx",
      Traffic511Parser::readWorkZoneFeed
    );
  }

  private URI endpoint(String path) throws IOException {
    var key = System.getenv(apiKeyEnvironmentVariable);
    if (key == null || key.isBlank()) {
      throw new IOException("511 API key environment variable is unset");
    }
    budget.reserve(Instant.now());
    return URI.create(HOST + path + "&api_key=" + URLEncoder.encode(key, StandardCharsets.UTF_8));
  }

  static final class RollingBudget {

    private final ArrayDeque<Instant> attempts = new ArrayDeque<>();

    synchronized void reserve(Instant now) throws IOException {
      Instant oldest = now.minus(Duration.ofHours(1));
      while (!attempts.isEmpty() && !attempts.getFirst().isAfter(oldest)) {
        attempts.removeFirst();
      }
      if (attempts.size() >= REQUESTS_PER_HOUR) {
        throw new IOException("511 traffic request budget exhausted");
      }
      attempts.addLast(now);
    }
  }
}
