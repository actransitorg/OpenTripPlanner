# Bay Area regional transit and road traffic

Use the consolidated regional GTFS for the timetable and the regional GTFS-Realtime feeds for
operator predictions, service alerts and vehicle locations. The `Traffic511` sandbox can also apply
reported closures and measured freeway speeds during street routing.
[511 regional transit feeds](https://511.org/open-data/transit)

## Build and serve regional transit

Use JDK 25 and Maven to build the shaded JAR. From the root of a fresh clone of the fork, run
these commands in PowerShell or Bash:

```shell
git switch traffic-511
mvn -pl otp-shaded -am package -DskipTests -Dps -Dcs
```

The JAR is `otp-shaded/target/otp-shaded-2.11.0-SNAPSHOT.jar`. For this packaging command,
`-Dps` skips Spotless formatting and `-Dcs` skips Checkstyle. To verify source changes, run
`mvn -pl otp-shaded -am package -DskipTests -PprettierCheck` with Node.js and npm on the
`PATH`. This checks formatting and runs Checkstyle.

Use a data directory with the following layout on either platform:

```text
otp/           build-config.json, router-config.json, Bay Area .osm.pbf, and the resulting graph.obj
511-snapshot/  RG.gtfs.zip, tripupdates.pb, alerts.pb, vehiclepositions.pb, captured-at.txt
511-live/      tripupdates.pb, alerts.pb, vehiclepositions.pb refreshed by the download process
```

The capture command creates `511-snapshot` and requests `operator_id=RG` for static GTFS and
`agency=RG` for TripUpdates, ServiceAlerts and VehiclePositions. Use `RG` as the OTP feed ID
for all four. Save the JSON below in the `otp` directory. OTP substitutes the two environment
variables before parsing the files. The Windows and container instructions below set them to
the paths visible to the Java process. To route around road closures, also enable the sandbox
in `otp-config.json` before building the graph. [Road configuration](#add-road-conditions-to-street-legs) · [OTP configuration substitution](../Configuration.md#system-environment-and-project-information-substitution)

**`build-config.json`**

```json
{
  "transitFeeds": [
    { "type": "gtfs", "feedId": "RG", "source": "${RG_STATIC_URI}" }
  ]
}
```

For live service, run the refresh command alongside OTP. It publishes each completed download
atomically. TripUpdates refresh every three minutes and ServiceAlerts and VehiclePositions every
ten minutes. Point `router-config.json` at the local files. OTP can read them once per minute
without consuming upstream quota. TripUpdates supply the stop-time predictions used for planning.
If the live feed stops providing fresh TripUpdates for 30 minutes, the refresh process withdraws
the old predictions so OTP can return to the timetable. Keep this process running alongside OTP.
The optional stop-sequence reconciliation below checks explicit stop IDs against the scheduled
trip. It can recover one-based positions and out-of-order records when the evidence uniquely
identifies each stop and supports its position on that trip.

**`router-config.json`**

```json
{
  "updaters": [
    { "type": "stop-time-updater", "feedId": "RG", "frequency": "1m",
      "reconcileStopSequence": true,
      "url": "${RG_RT_URI}/tripupdates.pb" },
    { "type": "real-time-alerts", "feedId": "RG", "frequency": "1m",
      "url": "${RG_RT_URI}/alerts.pb" },
    { "type": "vehicle-positions", "feedId": "RG", "frequency": "1m",
      "url": "${RG_RT_URI}/vehiclepositions.pb" }
  ]
}
```

### Windows PowerShell

From the repository root, set `JAVA_HOME` to the installed JDK 25 directory. Set `511_API_KEY`
in the account running the download commands. If you just saved a Windows user environment
variable, open a new PowerShell window to pick it up. Install `uv` for the Python commands.

```powershell
$data = 'C:\otp-data'
$otp = Join-Path $data 'otp'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$jar = (Resolve-Path 'otp-shaded/target/otp-shaded-2.11.0-SNAPSHOT.jar').Path
& $java -version
New-Item -ItemType Directory -Force $otp | Out-Null
uv run --with gtfs-realtime-bindings python script/capture-traffic-511.py --regional-transit "$data\511-snapshot"
$env:RG_STATIC_URI = ([uri](Join-Path $data '511-snapshot\RG.gtfs.zip')).AbsoluteUri
$env:RG_RT_URI = ([uri](Join-Path $data '511-live')).AbsoluteUri
```

Save the configuration and OSM extract in `$otp`. Start the following command in a second
PowerShell window from the repository root. Wait for the three realtime files to appear.

```powershell
uv run --with gtfs-realtime-bindings python script/capture-traffic-511.py --refresh-transit 'C:\otp-data\511-live'
```

Build and serve in the first window:

```powershell
& $java -jar $jar --build --save $otp
& $java -jar $jar --load --bindAddress 127.0.0.1 $otp
```

### Linux container

The example uses Bash on a Linux host, the branch JAR and a Temurin Java 25 Linux container.
From the repository root, create a host data directory and place the configuration and OSM
extract in its `otp` subdirectory. Run the capture helper on the host. If your secret manager
supplies a shell variable named `BAY_AREA_511_KEY`, `env` passes it to the helper under the
name `511_API_KEY`. If the process already inherits `511_API_KEY`, invoke `uv` directly.

```bash
data="$HOME/otp-data"
mkdir -p "$data/otp"
env "511_API_KEY=$BAY_AREA_511_KEY" uv run --with gtfs-realtime-bindings python script/capture-traffic-511.py --regional-transit "$data/511-snapshot"
```

Run the live refresh in a separate terminal or service with the same host data directory:

```bash
env "511_API_KEY=$BAY_AREA_511_KEY" uv run --with gtfs-realtime-bindings python script/capture-traffic-511.py --refresh-transit "$HOME/otp-data/511-live"
```

Once the feeds are present, build and serve with the following commands in the first terminal.
The directory mount makes atomic feed replacements visible to OTP. The build writes `graph.obj`
to the host's `otp` directory. Both commands use paths inside the container for the JSON settings.

```bash
jar="$(realpath otp-shaded/target/otp-shaded-2.11.0-SNAPSHOT.jar)"
otp_args=(--rm --entrypoint java
  --mount "type=bind,source=$jar,target=/opt/otp.jar,readonly"
  --mount "type=bind,source=$data,target=/data"
  --env RG_STATIC_URI=file:///data/511-snapshot/RG.gtfs.zip
  --env RG_RT_URI=file:///data/511-live)
docker run "${otp_args[@]}" eclipse-temurin:25-jre -jar /opt/otp.jar --build --save /data/otp
docker run "${otp_args[@]}" -p 127.0.0.1:8080:8080 eclipse-temurin:25-jre -jar /opt/otp.jar --load /data/otp
```

### Verify the service or replay a capture

On either platform, POST `{"query":"{ feeds { feedId } }"}` as `application/json` to
`http://127.0.0.1:8080/otp/gtfs/v1` and check that the response includes `RG`. For a fixed
replay, point `RG_RT_URI` at the captured `511-snapshot` directory, set each updater frequency
to `0s`, and plan for the time in `captured-at.txt`. A complete regional capture can be replayed
offline. The replay report includes a trip query and the expected change of connection.
[Bay Area replay results](Traffic511Replay.md)

Rebuild the graph when adopting a new static timetable. This branch uses graph serialization
version 293, so a graph built with version 292 also needs rebuilding.

## Add road conditions to street legs

Set the feature in `otp-config.json` **before building the OSM graph** and use the same setting
when serving it. The builder retains the OSM road identity needed to match live data.

```json
{
  "otpFeatures": { "Traffic511": true }
}
```

Set `511_API_KEY` in the OTP server's environment. Configure either or both live providers in
`router-config.json`. The 511 updater polls Events and Work Zones independently. The PeMS updater
refreshes the station directory daily and receives five-minute observations.

```json
{
  "updaters": [
    { "type": "traffic-511", "frequency": "10m", "maxAge": "30m" },
    { "type": "caltrans-pems", "frequency": "5m", "maxAge": "30m" }
  ]
}
```

Use `apiKeyEnvironmentVariable` to select a different variable for the 511 updater. The freshness
limit applies separately to each successful dataset. An incomplete refresh retains the previous
complete dataset until it expires. A complete empty feed clears the source. Each request uses
one consistent traffic snapshot. PeMS observations also expire at the traveler's traversal time.

For example, when the Linux host exports `BAY_AREA_511_KEY`, set
`"apiKeyEnvironmentVariable": "BAY_AREA_511_KEY"` on the `traffic-511` updater and add
`--env BAY_AREA_511_KEY` to the serving container's Docker arguments. For regional transit alone
the credential belongs to the download process.

511's default allotment is 60 requests per hour per key, shared across consumers and endpoints.
The road client allows 24 attempts per rolling hour per configured key variable in one process.
The live transit command makes up to 32 additional requests per hour. Budget other deployments,
one-off captures and consumers sharing a token separately. PeMS uses its own public endpoint.
[511 API access](https://511.org/open-data/traffic) · [Caltrans PeMS](https://pems.dot.ca.gov/)

## Replay a trip

1. Build a graph covering the test corridor. For transit trips, load the regional static GTFS
   matching the capture date and assign it the feed ID `RG`. [511 regional feeds](https://511.org/open-data/transit)
2. From the repository root, run the following command with the API key in the environment.
   Supply a new directory for each capture. The command saves the acquisition time, complete
   traffic datasets and all three regional GTFS-Realtime feeds.

   ```powershell
   $env:RG_ROAD_CAPTURE = (Join-Path $data 'replays/bay-area-01').Replace('\', '/')
   uv run --with gtfs-realtime-bindings python script/capture-traffic-511.py $env:RG_ROAD_CAPTURE
   $env:RG_RT_URI = ([uri]$env:RG_ROAD_CAPTURE).AbsoluteUri
   ```

   ```bash
   env "511_API_KEY=$BAY_AREA_511_KEY" uv run --with gtfs-realtime-bindings python script/capture-traffic-511.py "$data/replays/bay-area-01"
   ```

3. Use the replay updater below in place of the two live traffic updaters. It reads the directory
   on startup and evaluates feed freshness as of capture. Plan trips for the date and time in
   `captured-at.txt`. Point the standard transit updaters to the same capture. For the container
   example, set `--env RG_ROAD_CAPTURE=/data/replays/bay-area-01` and replace the `RG_RT_URI`
   argument with `--env RG_RT_URI=file:///data/replays/bay-area-01`. The replay updater takes a
   filesystem path. The GTFS-Realtime updaters take file URIs.

   ```json
   {
     "updaters": [
       { "type": "traffic-511-replay", "directory": "${RG_ROAD_CAPTURE}" },
       { "type": "stop-time-updater", "feedId": "RG", "frequency": "0s",
         "reconcileStopSequence": true,
         "url": "${RG_RT_URI}/tripupdates.pb" },
       { "type": "real-time-alerts", "feedId": "RG", "frequency": "0s",
         "url": "${RG_RT_URI}/alerts.pb" },
       { "type": "vehicle-positions", "feedId": "RG", "frequency": "0s",
         "url": "${RG_RT_URI}/vehiclepositions.pb" }
     ]
   }
   ```

4. Repeat the request against the same graph and transit feeds with the replay updater removed.
   For a closures-only comparison, set `"includeSpeeds": false` on the replay updater. The
   default is `true`. Compare the street geometry, elapsed time and connections. Keep synthetic fixtures in CI and
   store authenticated captures in the operator's data directory.

## Interpreting results

- A uniquely located point report can produce an advisory. Hard closure enforcement requires a
  matched extent. A WZDx detour feature describes an alternate alignment.
- The PeMS matcher uses mainline stations with positive speeds and samples and at least 50%
  observed data. Coverage is bounded by the mapped edge, nearby stations, and distance from the
  detector. Unsupported and stale observations fall back to the graph's car speeds.
- In either GraphQL API, request `streetTrafficNotices { source sourceId basis summary reportedAt }`
  on a leg for the evidence along the traveled path. Use the regional GTFS-Realtime feeds for
  agency predictions, cancellations and service alerts.
- Display linked “data provided by 511.org” attribution next to end-user results. The data
  agreement also requests documentation of public use within 30 days of launch.
  [511 Data Disseminator Agreement](https://511.org/sites/default/files/2026-04/511_Data_Agreement_Final_2026.pdf)

## Evaluating car travel times offline

Choose the directed corridor endpoints and evaluation dates before tuning the matcher. Keep
independent vehicle crossing times out of OTP's inputs. Compare the same graph, departure and
preferences three times with baseline OTP, closures only, and closures plus PeMS. Inspect the
geometry against the measured corridor. Leave a duration blank if the trip has no route or the
returned path takes another corridor. Count detours separately when evaluating route choice.

Use two UTF-8 CSV files. The observations header is
`observation_id,corridor,direction,departed_at,arrived_at`. Each row represents a measured vehicle
trip between the two gates. The predictions header is
`corridor,direction,departure,capture,baseline_seconds,closures_seconds,traffic_seconds`.
`capture` identifies the capture directory relative to the predictions CSV. Its `captured-at.txt`
must precede or equal the modeled departure. Use ISO 8601 timestamps with offsets in both files.

```shell
python script/score-traffic-511-car.py score observations.csv predictions.csv
```

The JSON report gives observation counts, coverage for each variant, and median and 90th-percentile
absolute errors for journeys with all three predictions. Scores are grouped by corridor and
direction. The 90th percentile uses the nearest rank. Exact departure matching is the default.
For a regular grid of forecasts, `--max-separation-seconds 60` allows the most recent modeled
departure up to one minute before the observed entry. Report the tolerance alongside the scores.

The US Department of Transportation published vehicle trajectories from video of eastbound I-80
in Emeryville. To reproduce a small historical sample, download the URL printed by the first
command to `frames.csv`, then run the second command. Both gates use the dataset's longitudinal
coordinate. The extractor interpolates the vehicle crossing between consecutive 0.1-second
mainline frames. [FHWA NGSIM trajectories and field definitions](https://data.transportation.gov/Automobiles/Next-Generation-Simulation-NGSIM-Vehicle-Trajector/8ect-6jqj)

```shell
python script/score-traffic-511-car.py sample-url
python script/score-traffic-511-car.py ngsim frames.csv observations.csv
```

The fixed April 13, 2005 sample yielded 35 complete passenger-car crossings from 100 to 1,400 ft.
The median observed duration was 64.956 s and the 90th percentile was 77.711 s. Scoring those
journeys requires predictions using inputs from that time. For a current pilot, collect
time-matched independent trips or obtain licensed agency probe observations for the same segments.
[FHWA probe data access](https://ops-dr.fhwa.dot.gov/publications/fhwahop20028/index.htm)

The Caltrans travel-time segment feed covers districts 3, 8, 11 and 12. District 4 publishes
changeable-message signs with rounded driving times derived from HERE probe estimates. These
offer a separate comparison with displayed predictions when sign destinations can be located
precisely. Individual observed crossing times are the basis for the benchmark above.
[Caltrans travel-time segments](https://cwwp2.dot.ca.gov/travel-times.html) ·
[District 4 sign feed](https://cwwp2.dot.ca.gov/data/d4/cms/cmsStatusD04.json) ·
[511 driving-time methodology](https://511.org/phone/tips/faq)

## Evaluating transit arrival estimates offline

The scoring experiment compares as-of forecasts with the monthly regional GTFS archive requested
with `historic=YYYY-MM-so`. Collect live captures throughout the month and retain the daily GTFS
used for the corresponding requests. The archive's observed arrivals can be derived from final
agency predictions or vehicle positions, so report agreement with observations along with their
uncertainty. [511 historical feeds](https://511.org/open-data/faqs) · [Observation methodology](https://www.interline.io/blog/mtc-regional-stop-observations/)

Build trials using the regional (`RG`) daily GTFS ZIP, the capture directory and a GeoJSON
FeatureCollection of reviewed road sections. For each section, extract an actual directed
mainline LineString from OSM in travel order. Verify the bus uses the same carriageway and
lane class as the detector, including through ramps, HOV lanes and interchanges. Attach the
following properties, using the daily RG trip shape, adjacent stop IDs and the captured
PeMS mainline station metadata. The example below covers 296 m of eastbound I-80 used by
AC Transit's F in the November 24, 2026 daily RG feed. Recheck the identifiers when replacing
the feed. Geometry © OpenStreetMap contributors, clipped from [way 123741276](https://www.openstreetmap.org/way/123741276).

```json
{
  "type": "FeatureCollection",
  "features": [{
    "type": "Feature",
    "properties": {
      "route_id": "AC:F", "shape_id": "AC:shp-F-09",
      "from_stop_id": "50032", "to_stop_id": "58617",
      "from_shape_sequence": 2250, "to_shape_sequence": 2310,
      "station_id": "401892", "freeway_id": "80", "freeway_dir": "E",
      "shared_mainline": true
    },
    "geometry": { "type": "LineString", "coordinates": [
      [-122.3095356, 37.8244830], [-122.3093646, 37.8245005],
      [-122.3078592, 37.8246587], [-122.3073634, 37.8247104],
      [-122.3070256, 37.8247457], [-122.3066931, 37.8247873],
      [-122.3063655, 37.8248343], [-122.3062049, 37.8248597]
    ] }
  }]
}
```

```shell
uv run --no-project --with shapely --with tzdata python script/traffic511_trials.py RG.gtfs.zip reviewed.geojson bay-area-01 --service-date 20261124 --history previous-capture-01 previous-capture-02 previous-capture-03 previous-capture-04 previous-capture-05 > trials.csv
```

The producer checks shape and road positions every 10 m. It accepts sections within 20 m in
travel order, up to 1 km long and within 30 m of the detector. It uses GTFS shape distances to
locate each section between the two stops. It compares current detector running time with the
median on at least five earlier dates in the last 90 days, in the same Pacific half-hour and
weekday/weekend group. It accumulates excess running time across consecutive qualifying hops,
retains scheduled dwell, and limits the forecast to 30 minutes from the road observation.
Include prior capture directories as history and concatenate the CSV rows from successive
capture/service-date runs under one header. The generator emits the following columns:

```csv
capture,agency_id,route_id,trip_start_date,trip_id,stop_sequence,stop_id,scheduled_arrival_time,road_excess_seconds,road_observed_at
bay-area-01,AC,AC:EXAMPLE,20261124,AC:example,12,AC:stop,24:20:00,90,2026-11-25T08:10:00Z
```

`capture` is an absolute directory or a path relative to the CSV. Use the identifiers and scheduled time from the matching daily
GTFS. For reviewed trips sharing a mapped road, supply the excess travel seconds accumulated up
to the stop compared with the typical running time on the same hops. Record the timestamp of
the road evidence available when the forecast was made. Leave both road columns empty if a
reviewed alignment or contemporaneous measurement is unavailable. The command reads operator
predictions directly from each capture's `tripupdates.pb`.

```shell
uv run --no-project --with gtfs-realtime-bindings --with tzdata python script/score-traffic-511-transit.py monthly-regional-so.zip trials.csv --holdout 20261116
```

Choose the holdout service date before examining results. The script fits the fraction of road
delay reflected in stop arrival delay separately by agency, route and lead time, using completed
training trips before the first held-out forecast. An operator update takes precedence for the
whole trip. The agency baseline uses explicit predictions for the target stop and falls back to
the schedule for partial updates with no target arrival. Each group's JSON report includes median and 90th-percentile absolute error in seconds,
coverage and sample counts, including a paired comparison on the held-out trips eligible for road
estimates. The default minimum is 30 distinct training trips per group. Reported evidence is for
offline evaluation. Live transit search continues to use agency predictions and schedules.

## Contact and changes

The sandbox is maintained on the AC Transit fork's `traffic-511` branch. Include the source
timestamp, travel mode and a reproducible trip request when reporting an issue to its maintainers.
November 2026 introduced the experimental graph metadata, street updaters and offline replay.
[AC Transit OpenTripPlanner fork](https://github.com/actransitorg/OpenTripPlanner/tree/traffic-511)
