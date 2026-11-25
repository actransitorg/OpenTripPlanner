package org.opentripplanner.ext.traffic511.feed;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import javax.net.ssl.HttpsURLConnection;
import org.jspecify.annotations.Nullable;

/** Read-only transport for the fixed, public agency HTTPS endpoints. URLs may contain secrets. */
class FeedHttp {

  @FunctionalInterface
  interface Decoder<T> {
    T read(InputStream input) throws IOException;
  }

  @FunctionalInterface
  interface CachedDecoder<T> {
    T read(InputStream input, Instant validatedAt) throws IOException;
  }

  private final int timeoutMillis;

  FeedHttp(Duration timeout) {
    this.timeoutMillis = Math.toIntExact(timeout.toMillis());
  }

  <T> T read(URI uri, String feed, Decoder<T> decoder) throws IOException, InterruptedException {
    return readCache(uri, feed, null, (input, receivedAt) -> decoder.read(input));
  }

  <T> T readCache(URI uri, String feed, @Nullable String key, CachedDecoder<T> decoder)
    throws IOException, InterruptedException {
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedException();
    }
    try {
      return download(uri, feed, key, decoder);
    } catch (FeedStatusException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      // HTTP, URI and JSON parsing errors can include a full URL or credential-bearing response.
      throw new IOException("Could not read " + feed + " (" + e.getClass().getSimpleName() + ")");
    }
  }

  <T> T download(URI uri, String feed, @Nullable String key, CachedDecoder<T> decoder)
    throws IOException {
    var response = (HttpsURLConnection) uri.toURL().openConnection();
    response.setConnectTimeout(timeoutMillis);
    response.setReadTimeout(timeoutMillis);
    response.setInstanceFollowRedirects(false);
    response.setRequestProperty("Accept-Encoding", "gzip, deflate");
    if (key != null) {
      response.setRequestProperty("X-Api-Key", key);
    }
    try {
      int status = response.getResponseCode();
      if (status != 200) {
        throw new FeedStatusException(feed, status);
      }
      var encoding = response.getContentEncoding();
      try (
        var raw = response.getInputStream();
        var input = decode(raw, encoding == null ? "" : encoding)
      ) {
        var validatedAt =
          key == null
            ? Instant.now()
            : Instant.parse(response.getHeaderField("X-Cache-Validated-At"));
        return decoder.read(input, validatedAt);
      }
    } finally {
      response.disconnect();
    }
  }

  static InputStream decode(InputStream raw, String encoding) throws IOException {
    return switch (encoding.toLowerCase(Locale.ROOT)) {
      case "identity", "" -> raw;
      case "gzip" -> new GZIPInputStream(raw);
      case "deflate" -> new InflaterInputStream(raw);
      default -> throw new IOException("Unsupported traffic feed encoding");
    };
  }

  private static final class FeedStatusException extends IOException {

    FeedStatusException(String feed, int status) {
      super(feed + " returned HTTP " + status);
    }
  }
}
