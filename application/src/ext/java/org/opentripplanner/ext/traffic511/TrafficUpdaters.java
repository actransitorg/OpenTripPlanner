package org.opentripplanner.ext.traffic511;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.opentripplanner.ext.traffic511.TrafficSnapshotPublisher.Source;
import org.opentripplanner.ext.traffic511.TrafficSnapshotPublisher.Update;
import org.opentripplanner.ext.traffic511.feed.PemsClient;
import org.opentripplanner.ext.traffic511.feed.PemsStation;
import org.opentripplanner.ext.traffic511.feed.Traffic511Client;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.updater.StreetRealTimeUpdateContext;
import org.opentripplanner.updater.spi.GraphUpdater;
import org.opentripplanner.updater.spi.PollingGraphUpdater;
import org.opentripplanner.updater.spi.PollingGraphUpdaterParameters;
import org.opentripplanner.updater.spi.WriteDomain;

/** Configures one polling task per dataset so a failed fetch cannot suppress a different source. */
public final class TrafficUpdaters {

  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

  public static List<GraphUpdater<?>> create(
    Graph graph,
    List<Traffic511Parameters> events,
    List<PemsParameters> pems,
    List<TrafficReplay.Parameters> replays
  ) {
    if (!replays.isEmpty()) {
      if (replays.size() != 1 || !events.isEmpty() || !pems.isEmpty()) {
        throw new IllegalArgumentException(
          "Configure a traffic replay by itself, with exactly one capture directory."
        );
      }
      return List.of(new TrafficReplay(matcherFor(graph), replays.getFirst()));
    }
    if (events.isEmpty() && pems.isEmpty()) {
      return List.of();
    }
    if (events.size() > 1 || pems.size() > 1) {
      throw new IllegalArgumentException("Configure each Bay Area traffic updater only once.");
    }
    var matcher = matcherFor(graph);
    var publisher = new TrafficSnapshotPublisher();
    List<GraphUpdater<?>> updaters = new ArrayList<>();
    for (var p : events) {
      var client = new Traffic511Client(p.cacheApiKeyEnvironmentVariable(), HTTP_TIMEOUT);
      updaters.add(
        new Poller(p, Source.EVENTS, publisher, () -> {
          var feed = client.fetchEvents(p.maxAge());
          return new Update(matcher.matchEvents(feed.events()), feed.expiresAt());
        })
      );
      updaters.add(
        new Poller(p, Source.WORK_ZONES, publisher, () -> {
          var feed = client.fetchWorkZoneFeed();
          return new Update(matcher.matchEvents(feed.events()), feed.issuedAt().plus(p.maxAge()));
        })
      );
    }
    for (var p : pems) {
      updaters.add(new Poller(p, Source.PEMS, publisher, new DetectorSource(matcher, p.maxAge())));
    }
    return updaters;
  }

  private static TrafficMatcher matcherFor(Graph graph) {
    var matcher = new TrafficMatcher(graph);
    if (!matcher.hasRoadIdentities()) {
      throw new IllegalArgumentException(
        "Build the street graph with the Traffic511 feature enabled before configuring traffic updaters."
      );
    }
    return matcher;
  }

  @FunctionalInterface
  private interface Loader {
    Update load() throws IOException, InterruptedException;
  }

  private static final class DetectorSource implements Loader {

    private final TrafficMatcher matcher;
    private final Duration maxAge;
    private final PemsClient client = new PemsClient(HTTP_TIMEOUT);
    private Map<String, PemsStation> stations = Map.of();
    private Instant refreshStationsAt = Instant.MIN;

    DetectorSource(TrafficMatcher matcher, Duration maxAge) {
      this.matcher = matcher;
      this.maxAge = maxAge;
    }

    @Override
    public Update load() throws IOException, InterruptedException {
      var now = Instant.now();
      if (!now.isBefore(refreshStationsAt)) {
        stations = client.fetchStations();
        refreshStationsAt = now.plus(Duration.ofDays(1));
      }
      var speeds = client.fetchSpeeds();
      return new Update(
        matcher.matchSpeeds(stations, speeds, maxAge),
        speeds.observedAt().plus(maxAge)
      );
    }
  }

  private static final class Poller extends PollingGraphUpdater<StreetRealTimeUpdateContext> {

    private final Source source;
    private final TrafficSnapshotPublisher publisher;
    private final Loader loader;

    Poller(
      PollingGraphUpdaterParameters p,
      Source source,
      TrafficSnapshotPublisher publisher,
      Loader loader
    ) {
      super(p);
      this.source = source;
      this.publisher = publisher;
      this.loader = loader;
    }

    @Override
    public WriteDomain<StreetRealTimeUpdateContext> writeDomain() {
      return WriteDomain.STREET;
    }

    @Override
    protected void runPolling() throws IOException, InterruptedException {
      var update = loader.load();
      updateGraph(context ->
        context.graph().setStreetTrafficSnapshotSource(publisher.replace(source, update))
      );
    }

    @Override
    public String toString() {
      return getConfigRef() + " (" + source + ")";
    }
  }
}
