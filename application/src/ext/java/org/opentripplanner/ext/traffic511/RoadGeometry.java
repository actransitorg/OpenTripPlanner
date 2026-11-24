package org.opentripplanner.ext.traffic511;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.opentripplanner.street.model.edge.StreetEdge;

/** Local metric projection for comparing Bay Area geometries and measuring offsets along them. */
final class RoadGeometry {

  private static final double Y_SCALE = Math.toRadians(1) * 6_371_010;
  private static final double X_SCALE = Y_SCALE * Math.cos(Math.toRadians(38));
  static final double LON_MARGIN = 25 / X_SCALE;
  static final double LAT_MARGIN = 25 / Y_SCALE;
  private static final AffineTransformation PROJECTION = AffineTransformation.scaleInstance(
    X_SCALE,
    Y_SCALE
  );

  final LengthIndexedLine line;
  final double length;

  RoadGeometry(LineString geographicLine) {
    var projected = PROJECTION.transform(geographicLine);
    this.line = new LengthIndexedLine(projected);
    this.length = projected.getLength();
  }

  static Coordinate project(Coordinate coordinate) {
    return new Coordinate(coordinate.x * X_SCALE, coordinate.y * Y_SCALE);
  }

  Coordinate tangent(double offset) {
    var a = line.extractPoint(Math.max(0, offset - 2));
    var b = line.extractPoint(Math.min(length, offset + 2));
    double norm = a.distance(b);
    return norm == 0
      ? new Coordinate(0, 0)
      : new Coordinate((b.x - a.x) / norm, (b.y - a.y) / norm);
  }

  static StreetEdge parent(StreetEdge edge) {
    while (edge.getParentEdge() != edge) {
      edge = edge.getParentEdge();
    }
    return edge;
  }

  /** Fractions are in the travel direction of the permanent parent, including for arrive-by. */
  record Span(double from, double to) {
    static final Span WHOLE = new Span(0, 1);

    static Span traversed(StreetEdge edge, StreetEdge parent) {
      if (edge == parent) {
        return WHOLE;
      }
      var original = new RoadGeometry(parent.getGeometry());
      var partial = edge.getGeometry();
      return new Span(
        original.line.project(project(partial.getCoordinateN(0))) / original.length,
        original.line.project(project(partial.getCoordinateN(partial.getNumPoints() - 1))) /
          original.length
      );
    }

    double size() {
      return to - from;
    }

    Span intersection(Span other) {
      return new Span(Math.max(from, other.from), Math.min(to, other.to));
    }
  }
}
