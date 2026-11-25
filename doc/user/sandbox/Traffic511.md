# Bay Area regional transit and road traffic

Use the consolidated regional GTFS for the timetable and the regional GTFS-Realtime feeds for
operator predictions, service alerts and vehicle locations. The `Traffic511` sandbox can also apply
reported closures and measured freeway speeds during street routing.
[511 regional transit feeds](https://511.org/open-data/transit)

## Build and serve regional transit

From the root of the fork checkout, select the branch with `git switch traffic-511`.

### Build the sandbox image

From Bash on a Linux Docker host with BuildKit, build the current checkout, including local
edits. The helper leaves the shaded JAR in `otp-shaded/target/` and tags the image
`otp-traffic-511:local`.

```bash
bash script/traffic-511/build_publish.sh
```

If this checkout already has `otp-shaded/target/otp-shaded-2.11.0-SNAPSHOT.jar` built from
the same revision, set `useJarShaded=1` to reuse it. To publish either build, log in to your
registry and supply your own repository and tag with `--publish`.

```bash
IMAGE=registry.example.org/transit/otp:traffic-511 bash script/traffic-511/build_publish.sh --publish
```

For a pipeline that calls `docker build` directly, use the repository root as the build context.
Choose one of these commands.

```shell
# Build the published traffic-511 branch with Maven and JDK 25.
docker build -f script/traffic-511/Containerfile --build-arg useJarShaded=0 -t otp-traffic-511:local .

# Package the shaded JAR and scripts from the current checkout.
docker build -f script/traffic-511/Containerfile --build-arg useJarShaded=1 -t otp-traffic-511:local .
```

Direct source builds fetch `traffic-511` from the AC Transit fork. Select a published revision
that includes the Java capture command and `prepare.sh` using `--build-arg OTP_REF=<full-commit-sha>`.
For local changes, use `build_publish.sh` or package a shaded JAR built from the same checkout.
Both modes produce a Temurin 25
Alpine JRE image with `/app/app.jar`, the traffic configuration, and port 8080.
The entrypoint supplies `/var/opentripplanner` when you pass `--build --save` or `--load --serve`.
The same JAR contains the live road updaters and the `Traffic511Capture` command used by
`/app/prepare.sh`. Java 25 is the only runtime dependency for serving, capture and refresh.

The former host command `script/capture-traffic-511.py` is deprecated. It forwards its
arguments to the Java command using the checkout's shaded JAR or the file named by `OTP_JAR`.
Use the Java or Docker examples below for new deployments.

### Build the JAR directly

Building from source runs Spotless and Java Checkstyle. To run the Maven build manually inside
`maven:3-eclipse-temurin-25`, start in the repository root. The first two commands put the
project's pinned Node.js and npm on `PATH` before Spotless starts. If Node 24 and npm are
already on `PATH`, run just the final command.

```bash
mvn -f client/pom.xml frontend:install-node-and-npm@install-node-and-npm
export PATH="$PWD/client/target/frontend/node:$PATH"
mvn -pl otp-shaded -am package -DskipTests
```

For a native Windows build, use PowerShell with JDK 25 and Maven.

```powershell
mvn -f client/pom.xml frontend:install-node-and-npm@install-node-and-npm
$env:Path = (Resolve-Path 'client/target/frontend/node').Path + [IO.Path]::PathSeparator + $env:Path
mvn -pl otp-shaded -am package -DskipTests
```

The result is `otp-shaded/target/otp-shaded-2.11.0-SNAPSHOT.jar`. To run tests and check
formatting as CI does, use `mvn test -PprettierCheck` in the same shell. If Spotless reports
`Can't automatically determine npm executable`, check `npm --version` there. Running
`mvn clean` removes the project-local toolchain, so run it before the setup commands.

### Configure cache access

All three capture modes (regional snapshot, live transit refresh, and road replay capture)
and the live `traffic-511` Java updater require two environment variables.

| Variable | Required value |
| --- | --- |
| `ACTRANSIT_CACHE_BASE_URL` | The environment's absolute HTTPS 511 feed prefix, including the path through `/feeds/511`. A trailing slash is optional. Omit credentials, query parameters and fragments. |
| `ACTRANSIT_CACHE_API_KEY` | The AC Transit Cache **client key** provisioned for OTP in that environment. The clients send it in the `X-Api-Key` header. An upstream `511_API_KEY` belongs to the cache collector. |

Set both variables in the process environment, or create a local `.env` file in the directory
where you run the commands. Replace both placeholders with your deployment values.

```dotenv
ACTRANSIT_CACHE_BASE_URL=https://cache.example.invalid/cache/v1/feeds/511
ACTRANSIT_CACHE_API_KEY=<OTP cache client key>
```

Git ignores `.env` and `.env.*`. Docker reads the file with `--env-file .env`. Java inherits
the environment of the shell or service that starts it. When using exported variables with Docker, replace `--env-file .env` with
`--env ACTRANSIT_CACHE_BASE_URL --env ACTRANSIT_CACHE_API_KEY`.

The cache client needs read access to the following routes relative to the configured prefix.

| Operation | Feeds required |
| --- | --- |
| Regional snapshot | `gtfs-static/rg` and all three regional realtime feeds below |
| Live transit refresh | `gtfs-rt/trip-updates/rg`, `gtfs-rt/service-alerts/rg`, `gtfs-rt/vehicle-positions/rg` |
| Road replay capture | `traffic/events`, `traffic/work-zones`, the three regional realtime feeds, and public Caltrans PeMS data |
| Live road updater | `traffic/events` and `traffic/work-zones` |

A cache HTTP 401 indicates a missing or invalid cache client key. A 403 means the key lacks
permission for that feed. A 503 means the cache has no snapshot it can serve for that feed.
Snapshot capture publishes the destination only after all required downloads succeed.
Serving a completed offline replay requires the captured files.

### Set up regional feeds

Use one data directory and mount it at `/var/opentripplanner` in the container.

```text
bayarea/
  build-config.json
  router-config.json
  otp-config.json
  bay-area.osm.pbf
  511-snapshot/    RG.gtfs.zip, tripupdates.pb, alerts.pb, vehiclepositions.pb, captured-at.txt
  511-live/        tripupdates.pb, alerts.pb, vehiclepositions.pb
  graph.obj        created by --build --save
```

Copy the three starter files from `script/traffic-511/config/` when setting up a new data
directory. Keep your edited copies for subsequent builds. They configure the regional
timetable and realtime updaters with feed ID `RG`. The image sets `RG_STATIC_URI` to
`file:///var/opentripplanner/511-snapshot/RG.gtfs.zip` and `RG_RT_URI` to
`file:///var/opentripplanner/511-live`. The Windows example sets equivalent local paths.
The starter configuration enables the road sandbox during graph build and registers the 511
and Caltrans PeMS Java updaters at serve time. When adopting it in an existing deployment,
merge those settings into your current configs and rebuild the graph. [Road configuration](#add-road-conditions-to-street-legs) · [OTP configuration substitution](../Configuration.md#system-environment-and-project-information-substitution)

For live service, run the refresh command alongside OTP. It publishes each completed download
atomically. TripUpdates refresh every three minutes and ServiceAlerts and VehiclePositions every
ten minutes. Point `router-config.json` at the local files. OTP can read them once per minute
without consuming upstream quota. TripUpdates supply the stop-time predictions used for planning.
If the live feed stops providing fresh TripUpdates for 30 minutes, the refresh process withdraws
the old predictions so OTP can return to the timetable. Keep this process running alongside OTP.
The optional stop-sequence reconciliation below checks explicit stop IDs against the scheduled
trip. It can recover one-based positions and out-of-order records when the evidence uniquely
identifies each stop and supports its position on that trip.

### Windows PowerShell

From the repository root, set `JAVA_HOME` to the installed Java 25 directory. If you keep
the cache settings in a local `.env`, load its two unquoted `NAME=value` entries into
each PowerShell session that will run capture, refresh or the live router.

```powershell
Get-Content -LiteralPath .env | ForEach-Object {
  if ($_ -match '^(ACTRANSIT_CACHE_BASE_URL|ACTRANSIT_CACHE_API_KEY)=(.+)$') {
    [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
  }
}
```

```powershell
$data = 'C:\otp-data'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$jar = (Resolve-Path 'otp-shaded/target/otp-shaded-2.11.0-SNAPSHOT.jar').Path
$capture = 'org.opentripplanner.ext.traffic511.feed.Traffic511Capture'
New-Item -ItemType Directory -Force $data | Out-Null
Get-ChildItem script/traffic-511/config -File -Filter '*.json' | ForEach-Object {
  $destination = Join-Path $data $_.Name
  if (-not (Test-Path -LiteralPath $destination)) {
    Copy-Item -LiteralPath $_.FullName -Destination $destination
  }
}
& $java -cp $jar $capture --regional-transit "$data\511-snapshot"
$env:RG_STATIC_URI = ([uri](Join-Path $data '511-snapshot\RG.gtfs.zip')).AbsoluteUri
$env:RG_RT_URI = ([uri](Join-Path $data '511-live')).AbsoluteUri
```

Place the Bay Area OSM extract directly in `$data`. Start the refresh command in a second
PowerShell window from the repository root. Wait for the three realtime files to appear.

```powershell
& "$env:JAVA_HOME\bin\java.exe" -cp 'otp-shaded/target/otp-shaded-2.11.0-SNAPSHOT.jar' org.opentripplanner.ext.traffic511.feed.Traffic511Capture --refresh-transit 'C:\otp-data\511-live'
```

Build the graph and serve it in the first window.

```powershell
& $java -jar $jar --build --save $data
& $java -jar $jar --load --bindAddress 127.0.0.1 $data
```

### Linux container

Put `ACTRANSIT_CACHE_BASE_URL` and `ACTRANSIT_CACHE_API_KEY` in the host's Git-ignored
`.env` file, one `NAME=value` per line. Place the Bay Area `.osm.pbf`
in the mounted data directory. From the Docker host, run the preparation container.

```bash
data=/opt/actransit/opentripplanner2.10/docker/graphs/bayarea
image=${IMAGE:-otp-traffic-511:local}
mkdir -p "$data"
docker run --rm --env-file .env -v "$data:/var/opentripplanner" \
  --entrypoint sh "$image" /app/prepare.sh
```

Preparation copies any missing starter configs, creates `511-snapshot` on the first run,
then keeps the three files under `511-live` current. If the initial capture fails, the
command exits and can be retried. Leave it running once the capture succeeds. In a second
terminal, build the graph with the same host directory and image.

```bash
data=/opt/actransit/opentripplanner2.10/docker/graphs/bayarea
image=${IMAGE:-otp-traffic-511:local}
docker run --rm -v "$data:/var/opentripplanner" "$image" --build --save
```

Once the graph and realtime files are present, start the service. This example uses the
deployment's `middleware-network`, static IP, log rotation and `.env` file. The host serves
OTP on port 9090. For a published image, set `IMAGE` to its full tag before the graph build.

```bash
docker run -d --name otp-traffic-511 --network middleware-network --ip 192.168.170.4 \
  --restart=unless-stopped --log-opt max-size=10m --log-opt max-file=5 --env-file .env \
  -p 9090:8080 -v "$data:/var/opentripplanner" "$image" --load --serve
```

### Verify the service or replay a capture

POST `{"query":"{ feeds { feedId } }"}` as `application/json` to
`http://127.0.0.1:9090/otp/gtfs/v1` for the container or
`http://127.0.0.1:8080/otp/gtfs/v1` for native Windows. Check that the response includes
`RG`. For an offline regional transit replay, point `RG_RT_URI` at the captured
`511-snapshot` directory, remove the two live road updaters from `router-config.json`, and
set the three transit updater frequencies to `0s`. Plan for the time in `captured-at.txt`.
To include captured road conditions, follow the road replay configuration below.
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

Set both cache environment variables on the OTP server. Configure either or both live providers in
`router-config.json`. The 511 updater polls the cache routes `traffic/events` and `traffic/work-zones`
independently. The cache collects and combines all upstream Events pages. The PeMS updater
refreshes the station directory daily and receives five-minute observations.

```json
{
  "updaters": [
    { "type": "traffic-511", "frequency": "10m", "maxAge": "30m" },
    { "type": "caltrans-pems", "frequency": "5m", "maxAge": "30m" }
  ]
}
```

Use `cacheApiKeyEnvironmentVariable` to select a different cache key variable for the 511 updater.
When migrating a router with `apiKeyEnvironmentVariable`, replace that setting and provision the
cache client credential in the selected variable. The freshness
limit applies separately to each successful dataset. An incomplete refresh retains the previous
complete dataset until it expires. A complete empty feed clears the source. Each request uses
one consistent traffic snapshot. PeMS observations also expire at the traveler's traversal time.

The cache manages the upstream 511 credential and collection quota. PeMS uses its own public
endpoint. [Caltrans PeMS](https://pems.dot.ca.gov/)

## Replay a trip

1. Build a graph covering the test corridor. For transit trips, load the regional static GTFS
   matching the capture date and assign it the feed ID `RG`. [511 regional feeds](https://511.org/open-data/transit)
2. From the repository root, run the following command with both cache variables in the environment.
   Supply a new directory for each capture. The command saves the acquisition time, complete
   traffic datasets and all three regional GTFS-Realtime feeds.

   ```powershell
   $env:RG_ROAD_CAPTURE = (Join-Path $data 'replays/bay-area-01').Replace('\', '/')
   & $java -cp $jar $capture $env:RG_ROAD_CAPTURE
   $env:RG_RT_URI = ([uri]$env:RG_ROAD_CAPTURE).AbsoluteUri
   ```

   ```bash
   docker run --rm --env-file .env -v "$data:/var/opentripplanner" --entrypoint java "$image" \
     -cp /app/app.jar org.opentripplanner.ext.traffic511.feed.Traffic511Capture \
     /var/opentripplanner/replays/bay-area-01
   ```

3. Use the replay updater below in place of the two live traffic updaters. It reads the directory
   on startup and evaluates feed freshness as of capture. Plan trips for the date and time in
   `captured-at.txt`. Point the standard transit updaters to the same capture. For the container
   example, add `--env RG_ROAD_CAPTURE=/var/opentripplanner/replays/bay-area-01` and
   `--env RG_RT_URI=file:///var/opentripplanner/replays/bay-area-01`. The replay updater takes a
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
