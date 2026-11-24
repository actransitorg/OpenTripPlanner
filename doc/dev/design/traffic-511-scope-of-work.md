# Traffic-aware multimodal trip planning for the San Francisco Bay Area

## Objective

The `traffic-511` sandbox will use Bay Area 511 road events, transit operator predictions and
Caltrans freeway speeds to improve the routes and arrival times OpenTripPlanner gives travelers.
The first milestone is a replayable demonstration against baseline OTP. It should show a driver
avoiding a closed ramp, a cyclist or wheelchair user finding a usable alternative, and a transit
rider making a different connection when conditions change. A deployment pilot follows once the
replay demonstrates those outcomes.

Use the consolidated regional feed throughout. Download the active GTFS timetable with
`operator_id=RG` and TripUpdates, ServiceAlerts and VehiclePositions with `agency=RG`. Assign
the static feed and all three realtime updaters the same OTP feed ID, `RG`. Compare static-only
and agency-updated plans first, then measure the incremental effects of road conditions on the
same regional itineraries. [Regional replay results](../../user/sandbox/Traffic511Replay.md)

## Evidence from the feeds

The following results came from a read-only sample on November 24, 2026. Counts describe the sampled
interval. The replay must establish coverage across corridors, modes and times of day.

| Source | Observed in the sample | Routing use |
| --- | --- | --- |
| 511 Traffic Events | All 25 active events and all 500 archived events sampled had point geometry. One active event reported a closed road. None of the sampled road advisories gave a numeric delay. The endpoint required gzip or deflate. | Show a relevant advisory for a point report. Enforce a closure when the affected facility and extent can be independently identified. [Traffic specification](https://511.org/sites/default/files/2026-09/511%20SF%20Bay%20Open%20Data%20Specification%20-%20Traffic.pdf) |
| 511 Work Zone Data Exchange (WZDx) | A version 4.1 snapshot covering active and future events contained 3,660 features, including 3,536 lines and 55 detours. None supplied individual lane records. | Use the feature type, vehicle impact, geometry, direction and schedule to locate closures. Detour geometry describes a possible alternate route. [511 traffic portal](https://511.org/open-data/traffic) · [WZDx road event schema](https://raw.githubusercontent.com/usdot-jpo-ode/wzdx/main/schemas/4.1/RoadEventFeature.json) |
| Caltrans PeMS District 4 | All 3,905 records in the 08:25 file matched station IDs in the directory. Of 2,291 mainline stations, 1,234 had positive speed and sample count and at least 50% observed data. | Use recent speeds from qualifying detectors on the matched carriageway. A populated speed field can include imputed data. [PeMS downloads and field definitions](https://pems.dot.ca.gov/) |
| 511 regional transit | The regional GTFS-Realtime feed returned 4,090 trip updates, including 3,727 with stop-time updates. | Pair the regional static GTFS feed (`operator_id=RG`) with regional TripUpdates and ServiceAlerts (`agency=RG`). Coverage depends on the reporting operator. [511 transit feeds](https://511.org/open-data/transit) |

## Implementation work

### Feed handling and publication

- Add a default-off `Traffic511` sandbox feature, a `traffic-511` polling updater for Events and
  WZDx, and a separate `caltrans-pems` updater. Provide a Bay Area configuration that also uses
  OTP's existing GTFS-Realtime updaters. Keep provider code and tests in the sandbox source roots.
  [OTP sandbox contract](../../user/SandboxExtension.md)
- Resolve the 511 credential from `511_API_KEY` in the updater process. Keep credential-bearing URLs
  out of configuration dumps, logs, status responses and replay files. Budget traffic and transit
  requests together against the default 60 requests per hour per token, including pages and retries.
  Fetch complete pages over HTTPS and decode compressed responses. [511 API access and limits](https://511.org/open-data/traffic)
- Fetch, parse and match off the writer thread. Publish each complete source update atomically on
  OTP's street write domain. Track source timestamps and last successful fetch independently. A
  failed page retains the previous complete snapshot until its freshness limit. A successful empty
  snapshot clears the source. Expire stale speeds and unscheduled incidents, and honor closure end
  times, recurring local schedules and date exceptions.
- Pin an immutable street snapshot per planning request. Direct routes, transit access and egress,
  transfers, and the final itinerary must all see the same data version. Keep live data outside the
  serialized graph. [OTP request scope](../../../application/src/main/java/org/opentripplanner/standalone/configure/RequestScopedModule.java)

### Matching and street traversal

- Build a graph-specific index of directed OSM segments with way ID, route reference, road class,
  ramp status, facility type and available level or structure tags. Match connected geometry,
  signed direction and road identity together. Record the reason for each enforced or advisory-only
  match. Test divided highways, stacked roads, shared route numbers and paths alongside bridges.
- Reconcile overlapping Events and WZDx reports by facility, direction, extent and time. Apply a
  hard closure only when its meaning and match are unambiguous. A closed vehicle lane, sidewalk,
  cycle lane and shared-use path each affect different travelers. Preserve legal dismounts and
  wheelchair-accessible alternatives. An unknown direction or ambiguous point yields an advisory.
- Add a provider-neutral hook to street traversal that can reject an edge for a specific mode or
  change its elapsed time and routing cost. Preserve the existing DataOverlay cost extension so
  both features can operate together. Evaluate the time the traveler occupies the affected segment
  in both depart-at and arrive-by searches. Map temporary partial edges to the relevant
  portion of their parent so starts and destinations midway along a road behave correctly.
  [OTP street traversal](../../../street/src/main/java/org/opentripplanner/street/model/edge/StreetEdge.java)
- Read the PeMS live file's timestamp header and 11-field station rows. Join station metadata, check
  freshness and observation quality, and convert mph to OTP's speed units. Bound a station's
  coverage by nearby stations, junctions and carriageway. Where a feed gives an explicit incident
  delay, charge it once across the matched extent and avoid double counting measured congestion.
  Fall back to OTP street speeds when live evidence expires or coverage ends.

### Whole-journey routing

- Give access and egress paths validity windows so RAPTOR can use them at the time it places the
  street leg. Keep a viable detour to the same stop. For intermediate transfers, carry the actual
  transfer time and selected street path through search, optimization and itinerary mapping.
  Exercise park-and-ride, vehicle rentals and street-routed flexible service through their normal
  entry points. [RAPTOR transfer interface](../../../raptor/router/src/main/java/org/opentripplanner/raptor/spi/RaptorTransfer.java)
- Use operator updates for each trip and service date, including partial updates, diversions and
  cancellations. For fixed-schedule trips without an operator update, evaluate a separate estimate
  on verified shared-road segments. Use excess travel time above typical running time, preserve
  dwell and stop order, and apply the estimate before RAPTOR evaluates connections. Require an
  actual transit shape matched to the roadway. Rail, ferry and dedicated-right-of-way service can
  benefit through operator updates and better connecting legs.
- Expose the affected leg, source, reporting time and basis for an advisory or estimate. Distinguish
  agency predictions from OTP road-based estimates. Include linked “data provided by 511.org”
  attribution near results and document MTC's public-launch reporting requirement in the operator
  guide. [511 Data Disseminator Agreement](https://511.org/sites/default/files/2026-04/511_Data_Agreement_Final_2026.pdf)

## Replay and acceptance

| Scenario | Expected result |
| --- | --- |
| Oakland westbound I-580 to eastbound I-80 overnight ramp closure | Detour only the closed movement during its operating hours. Verify the opposite carriageway, adjacent open ramps, reopening, and a trip starting on a partial edge. [Caltrans closure and detour](https://dot.ca.gov/caltrans-near-me/district-4/d4-news/2023-04-26-wb580-eb80-interchange-closure) |
| Richmond–San Rafael Bridge bicycle and pedestrian path closure | A confirmed path closure changes walking, wheelchair and cycling itineraries while the open vehicle carriageway remains usable. Where the request permits transit, offer a feasible crossing subject to accessibility and bicycle carriage rules. [511 bridge access](https://511.org/travel/biking-walking/access/bridges) |
| A station access path closes during a train ride | Select the usable entrance or transfer path at the time the rider reaches it. Show its duration and choose a later connection when necessary. Repeat with an arrive-by request and a reopening. |
| A shared-road transit trip encounters congestion | A qualified estimate can change a connection. An operator update takes precedence as soon as it arrives. Adjacent rail and ferry services retain their operator or scheduled times. |

1. Capture timestamped inputs as they become available and replay each request using only evidence
   available at that instant. Compare baseline OTP, closure-aware OTP and closure-plus-speed OTP
   with the same graph, preferences and agency predictions. Use synthetic fixtures for deterministic
   CI and authenticated local captures for the Bay Area evaluation.
2. Report closure match coverage and false closures by facility and direction. Score car travel
   times against held-out corridor observations. Score transit arrivals against the matching
   monthly regional GTFS `stop_observations.txt`, accounting for historical trip IDs, service dates
   and observation uncertainty. Report median and 90th-percentile absolute arrival error, missed
   connections, sample size and coverage by mode and corridor. [511 historical observations](https://511.org/open-data/faqs)
3. Require all reviewed closure and accessibility scenarios to pass. Enable measured freeway
   speeds on corridors where held-out travel times improve. Qualify road-based transit estimates
   separately for each evaluated group against the agency-plus-schedule baseline. Leave groups
   with insufficient evidence on their existing prediction source. Run Java 25 unit and
   integration tests, concurrent-refresh and feed-outage cases, feature-disabled comparisons, and
   the [OTP routing performance benchmark](../../../test/performance/README.md).
4. Deliver the sandbox modules and core hooks, token-free fixtures, a capture/replay command,
   example configuration, an operator guide and a before-and-after report. For each demonstration
   trip show the route geometry, arrival time and connection gained or lost. Use these results to
   decide which corridors and capabilities are ready for a deployment pilot.
