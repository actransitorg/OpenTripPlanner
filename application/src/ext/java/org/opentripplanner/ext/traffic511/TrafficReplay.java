package org.opentripplanner.ext.traffic511;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.opentripplanner.ext.traffic511.TrafficSnapshotPublisher.Source;
import org.opentripplanner.ext.traffic511.TrafficSnapshotPublisher.Update;
import org.opentripplanner.ext.traffic511.feed.PemsParser;
import org.opentripplanner.ext.traffic511.feed.Traffic511Parser;
import org.opentripplanner.street.model.traffic.StreetTrafficSnapshot;
import org.opentripplanner.updater.StreetRealTimeUpdateContext;
import org.opentripplanner.updater.spi.PollingGraphUpdater;
import org.opentripplanner.updater.spi.PollingGraphUpdaterParameters;
import org.opentripplanner.updater.spi.WriteDomain;

/** Loads one local capture and evaluates source freshness at its capture time for reproducible trips. */
public final class TrafficReplay extends PollingGraphUpdater<StreetRealTimeUpdateContext> {

  public record Parameters(
    String configRef,
    Path directory,
    Duration maxAge,
    boolean includeSpeeds
  ) implements PollingGraphUpdaterParameters {
    @Override
    public Duration frequency() {
      return Duration.ZERO;
    }
  }

  private final TrafficMatcher matcher;
  private final Parameters params;

  TrafficReplay(TrafficMatcher matcher, Parameters params) {
    super(params);
    this.matcher = matcher;
    this.params = params;
  }

  @Override
  public WriteDomain<StreetRealTimeUpdateContext> writeDomain() {
    return WriteDomain.STREET;
  }

  @Override
  protected void runPolling() throws IOException {
    var snapshot = load(matcher, params);
    updateGraph(context -> context.graph().setStreetTrafficSnapshotSource(_ -> snapshot));
  }

  static StreetTrafficSnapshot load(TrafficMatcher matcher, Parameters params) throws IOException {
    var dir = params.directory();
    var asOf = Instant.parse(Files.readString(dir.resolve("captured-at.txt")).strip());
    var publisher = new TrafficSnapshotPublisher();
    try (var input = Files.newInputStream(dir.resolve("events.json"))) {
      var events = Traffic511Parser.readEvents(input, asOf.plus(params.maxAge()));
      if (events.nextOffset() >= 0) {
        throw new IOException("Replay requires a complete Events dataset");
      }
      publisher.replace(
        Source.EVENTS,
        new Update(matcher.matchEvents(events.events()), Instant.MAX)
      );
    }
    try (var input = Files.newInputStream(dir.resolve("work-zones.json"))) {
      var feed = Traffic511Parser.readWorkZoneFeed(input);
      var roadEvents = publisher.replace(
        Source.WORK_ZONES,
        new Update(matcher.matchEvents(feed.events()), feed.issuedAt().plus(params.maxAge()))
      );
      if (!params.includeSpeeds()) {
        return roadEvents.apply(asOf);
      }
    }
    try (
      var stationInput = Files.newInputStream(dir.resolve("stations.xml"));
      var speedInput = Files.newInputStream(dir.resolve("speeds.txt"))
    ) {
      var speeds = PemsParser.readSpeeds(speedInput, asOf);
      var stations = PemsParser.readStations(stationInput);
      var update = new Update(
        matcher.matchSpeeds(stations, speeds, params.maxAge()),
        speeds.observedAt().plus(params.maxAge())
      );
      return publisher.replace(Source.PEMS, update).apply(asOf);
    }
  }
}
