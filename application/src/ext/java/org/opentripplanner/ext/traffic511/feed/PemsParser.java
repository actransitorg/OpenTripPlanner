package org.opentripplanner.ext.traffic511.feed;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.HashMap;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

public final class PemsParser {

  private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern(
    "MM/dd/uuuu HH:mm:ss"
  ).withResolverStyle(ResolverStyle.STRICT);
  private static final double METERS_PER_SECOND_PER_MPH = 0.44704;

  private PemsParser() {}

  /** Reads the timestamp-header, 11-column live District 4 format. */
  public static PemsSpeeds readSpeeds(InputStream data, Instant receivedAt) throws IOException {
    var reader = new BufferedReader(new InputStreamReader(data, StandardCharsets.UTF_8));
    var header = reader.readLine();
    if (header == null) {
      throw new IOException("Missing PeMS timestamp");
    }
    var local = LocalDateTime.parse(header.strip().replace("\uFEFF", ""), TIMESTAMP);
    var offsets = Open511Schedule.PACIFIC.getRules().getValidOffsets(local);
    // At the fall-back hour either offset is possible. Use the latest interval that has begun.
    var observedAt = offsets
      .stream()
      .map(local::toInstant)
      .filter(time -> !time.isAfter(receivedAt))
      .max(Instant::compareTo)
      .orElseThrow(() -> new IOException("Invalid or future PeMS timestamp"));
    var speeds = new HashMap<String, Double>();
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.isBlank()) {
        continue;
      }
      var fields = line.split(",", -1);
      if (fields.length != 11 || fields[0].isBlank()) {
        throw new IOException("Unexpected PeMS live station record format");
      }
      var speedMph = number(fields[3]);
      var samples = number(fields[9]);
      var observed = number(fields[10]);
      if (
        speedMph > 0 &&
        Double.isFinite(speedMph) &&
        samples > 0 &&
        Double.isFinite(samples) &&
        observed >= 50 &&
        observed <= 100
      ) {
        speeds.put(fields[0], speedMph * METERS_PER_SECOND_PER_MPH);
      }
    }
    return new PemsSpeeds(observedAt, speeds);
  }

  /** Reads the statewide station directory as a stream and retains District 4 mainline stations. */
  public static Map<String, PemsStation> readStations(InputStream data) throws IOException {
    var factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    try {
      var xml = factory.createXMLStreamReader(data);
      try {
        return stations(xml);
      } finally {
        xml.close();
      }
    } catch (XMLStreamException e) {
      throw new IOException("Invalid PeMS station XML", e);
    }
  }

  private static Map<String, PemsStation> stations(XMLStreamReader xml)
    throws XMLStreamException, IOException {
    var stations = new HashMap<String, PemsStation>();
    boolean district4 = false;
    boolean foundDistrict = false;
    while (xml.hasNext()) {
      int token = xml.next();
      if (token == XMLStreamConstants.END_ELEMENT && xml.getLocalName().equals("district")) {
        district4 = false;
      }
      if (token != XMLStreamConstants.START_ELEMENT) {
        continue;
      }
      if (xml.getLocalName().equals("district")) {
        district4 = "4".equals(xml.getAttributeValue(null, "id"));
        foundDistrict |= district4;
      } else if (
        district4 &&
        xml.getLocalName().equals("vds") &&
        "ML".equals(xml.getAttributeValue(null, "type"))
      ) {
        var id = xml.getAttributeValue(null, "id");
        var route = xml.getAttributeValue(null, "freeway_id");
        var signedDirection = xml.getAttributeValue(null, "freeway_dir");
        if (id == null || route == null || signedDirection == null) {
          continue;
        }
        var direction = TrafficEvent.Direction.parse(signedDirection);
        var lon = number(xml.getAttributeValue(null, "longitude"));
        var lat = number(xml.getAttributeValue(null, "latitude"));
        if (
          direction == TrafficEvent.Direction.UNKNOWN ||
          direction == TrafficEvent.Direction.BOTH ||
          !(Math.abs(lon) <= 180 && Math.abs(lat) <= 90)
        ) {
          continue;
        }
        stations.put(
          id,
          new PemsStation(
            id,
            route,
            direction,
            lon,
            lat,
            number(xml.getAttributeValue(null, "abs_pm"))
          )
        );
      }
    }
    if (!foundDistrict) {
      throw new IOException("Missing District 4 in PeMS station directory");
    }
    return Map.copyOf(stations);
  }

  private static double number(String text) {
    if (text == null || text.isBlank()) {
      return Double.NaN;
    }
    try {
      return Double.parseDouble(text);
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }
}
