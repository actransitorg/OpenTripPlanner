package org.opentripplanner.ext.traffic511.feed;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import javax.net.ssl.HttpsURLConnection;

/** Read-only transport for the fixed, public agency HTTPS endpoints. URLs may contain secrets. */
final class FeedHttp {

  @FunctionalInterface
  interface Decoder<T> {
    T read(InputStream input) throws IOException;
  }

  private final int timeoutMillis;

  FeedHttp(Duration timeout) {
    this.timeoutMillis = Math.toIntExact(timeout.toMillis());
  }

  <T> T read(URI uri, String feed, Decoder<T> decoder) throws IOException, InterruptedException {
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedException();
    }
    try {
      return download(uri, feed, decoder);
    } catch (FeedStatusException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      // HTTP, URI and JSON parsing errors can include a full URL or credential-bearing response.
      throw new IOException("Could not read " + feed + " (" + e.getClass().getSimpleName() + ")");
    }
  }

  private <T> T download(URI uri, String feed, Decoder<T> decoder) throws IOException {
    var response = (HttpsURLConnection) uri.toURL().openConnection();
    response.setConnectTimeout(timeoutMillis);
    response.setReadTimeout(timeoutMillis);
    response.setInstanceFollowRedirects(false);
    response.setRequestProperty("Accept-Encoding", "gzip, deflate");
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
        return decoder.read(input);
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
