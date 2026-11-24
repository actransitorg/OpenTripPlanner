package org.opentripplanner.street.model.traffic;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Provenance for a traffic report or speed observation on a traveled section of a street leg. */
public record StreetTrafficNotice(
  String source,
  String sourceId,
  String basis,
  String summary,
  @Nullable Instant reportedAt
) {}
