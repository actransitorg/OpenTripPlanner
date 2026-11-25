package org.opentripplanner.ext.traffic511.feed;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public final class Traffic511Client {

  private final URI cache;
  private final Supplier<String> cacheKey;
  private final FeedHttp http;

  public Traffic511Client(String cacheApiKeyEnvironmentVariable, Duration timeout) {
    this(
      cacheBase(System.getenv("ACTRANSIT_CACHE_BASE_URL")),
      () -> System.getenv(cacheApiKeyEnvironmentVariable),
      new FeedHttp(timeout)
    );
  }

  Traffic511Client(URI cache, Supplier<String> cacheKey, FeedHttp http) {
    this.cache = cache;
    this.cacheKey = cacheKey;
    this.http = http;
  }

  static URI cacheBase(@Nullable String value) {
    if (value != null) {
      try {
        var base = URI.create(value.strip());
        if (
          "https".equalsIgnoreCase(base.getScheme()) &&
          base.getHost() != null &&
          base.getUserInfo() == null &&
          base.getRawQuery() == null &&
          base.getRawFragment() == null
        ) {
          return URI.create(base.toString().replaceFirst("/+$", "") + "/");
        }
      } catch (IllegalArgumentException ignored) {
        // Invalid input can contain credentials.
      }
    }
    throw new IllegalArgumentException(
      "Set ACTRANSIT_CACHE_BASE_URL to the HTTPS base URL for the 511 feeds."
    );
  }

  public record EventFeed(List<TrafficEvent> events, Instant expiresAt) {}

  public EventFeed fetchEvents(Duration maxAge) throws IOException, InterruptedException {
    return http.readCache(
      cache.resolve("traffic/events"),
      "511 Events",
      key(),
      (input, validatedAt) -> {
        var expiresAt = validatedAt.plus(maxAge);
        var page = Traffic511Parser.readEvents(input, expiresAt);
        if (page.nextOffset() != -1) {
          throw new IOException("Incomplete cached Events dataset");
        }
        return new EventFeed(page.events(), expiresAt);
      }
    );
  }

  public Traffic511Parser.WorkZoneFeed fetchWorkZoneFeed()
    throws IOException, InterruptedException {
    return http.readCache(
      cache.resolve("traffic/work-zones"),
      "511 WZDx",
      key(),
      (input, validatedAt) -> Traffic511Parser.readWorkZoneFeed(input)
    );
  }

  <T> T readFeed(String route, FeedHttp.Decoder<T> decoder)
    throws IOException, InterruptedException {
    return readCachedFeed(route, (input, validatedAt) -> decoder.read(input));
  }

  <T> T readCachedFeed(String route, FeedHttp.CachedDecoder<T> decoder)
    throws IOException, InterruptedException {
    return http.readCache(cache.resolve(route), route, key(), decoder);
  }

  String key() throws IOException {
    var key = cacheKey.get();
    if (key == null || key.isBlank()) {
      throw new IOException("AC Transit Cache client key environment variable is unset");
    }
    return key;
  }
}
