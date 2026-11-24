package org.opentripplanner.street.model;

import java.io.Serializable;
import org.jspecify.annotations.Nullable;

/** Original OSM tags used for matching external road reports. Shared by all segments of a way. */
public record OsmWayIdentity(
  long wayId,
  @Nullable String name,
  @Nullable String ref,
  @Nullable String highway,
  @Nullable String footway,
  @Nullable String layer,
  @Nullable String bridge,
  @Nullable String tunnel,
  @Nullable String forwardDirection,
  @Nullable String backwardDirection,
  @Nullable String hov
) implements Serializable {}
