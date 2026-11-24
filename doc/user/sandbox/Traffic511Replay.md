# Bay Area regional replay, November 24, 2026

## Regional transit

The OTP graph used the complete active regional GTFS with feed ID `RG`, the November 24 service day,
and an Oakland/Emeryville OSM extract. The timetable included 41 agencies and 665 routes. The street
extract limits door-to-door walking coverage. The transit comparison below used stop IDs as
endpoints. All times are Pacific.

At 08:56:10 the regional feed contained 4,013 TripUpdates. All 4,013 trip IDs matched trips active
in the regional timetable. With default stop matching OTP applied 3,835 updates (95.56%).
With `reconcileStopSequence` enabled it applied 3,991 (99.45%). Confirming one-based positions
against explicit stop IDs recovered 129 updates and ordering uniquely identified stops
recovered another 27. The remaining 22 contained invalid stop sequences or negative inter-stop
running times. These counts measure how many agency updates OTP can apply. Prediction accuracy
requires observed arrivals.

The check also corrects some updates that previously passed validation. For trip
`RG:SF:12134352_M21`, the agency paired sequence 8 with stop `16993` and a time of 08:55:52.
In the static timetable, the eighth stop is `16993`, scheduled at 08:56:03. Its GTFS sequence
number is different. Looking up GTFS sequence 8 directly would attach that prediction to stop
`16991`, scheduled at 08:51:00.

| Request                                                                                                                         | Static RG                                                                                                               | Same graph with RG TripUpdates                                                                                                                                                     |
| ------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| AC Transit J, trip `RG:AC:557020`, stop `RG:50528` in Emeryville to `RG:50019` at Salesforce Transit Center, depart after 08:58 | 09:01:00 to 09:27:00                                                                                                    | 09:01:00 to 09:20:56. The selected bus and geometry were the same.                                                                                                                 |
| AC Transit 57, trip `RG:AC:8099020`, stop `RG:58617` at Christie Avenue and Powell Street Plaza                                 | 09:12:55 arrival                                                                                                        | 09:14:50 predicted arrival, 115 seconds later.                                                                                                                                     |
| From `RG:53836` (14th Avenue and East 21st Street) to `RG:51828` (11th and Clay), depart after 09:11:20                         | Route 96 `RG:AC:469020` to 12th and Oak at 09:30:24. Board route 40 `RG:AC:4462020` at 09:32:42 and arrive at 09:37:00. | Route 96 is predicted to depart the origin at 09:28:54. Stay on board to 12th and Harrison at 09:42:29, then take the later 40 `RG:AC:8153020` at 09:52:52 and arrive at 09:56:42. |

The third request changed both the transfer stop and the connecting bus. At 12th and Oak the
scheduled connection had 2 minutes 18 seconds of transfer time. The agency predicted a 09:41:33
arrival for the 96 and a 09:30:58 departure for the original 40, a missed connection of 10 minutes
35 seconds. The revised itinerary arrived 19 minutes 42 seconds later. To replay it, use its stop
IDs, set the time to `09:11:20`, and replace the bus mode with `TRANSIT`.

To repeat the first request against a saved capture, send this query to `/otp/gtfs/v1` with the RG
updaters enabled, then against the same graph with those updaters removed.

```graphql
{
  plan(
    fromPlace: "RG:50528"
    toPlace: "RG:50019"
    date: "2026-11-24"
    time: "08:58:00"
    transportModes: [{ mode: BUS }, { mode: WALK }]
  ) {
    itineraries {
      legs {
        mode
        trip {
          gtfsId
        }
        startTime
        endTime
        realTime
        legGeometry {
          points
        }
      }
    }
  }
}
```

A separate regional-only capture at 09:16:07 returned the static archive, 4,031 TripUpdates, 115
ServiceAlerts and 1,986 VehiclePositions. OTP applied 4,006 TripUpdates (99.38%). One BART
added trip supplied a blank route ID and only one stop. OTP rejected that trip and processed the
rest of the batch. The GraphQL API returned all 115 alerts. OTP applied
1,548 vehicle positions. Of the remainder, 424 lacked a trip descriptor and 14 referenced trips
absent from the matching regional timetable. The capture tool strips credential-bearing ZIP
metadata while preserving GTFS table contents.

An outage replay on the running server advanced the refresh clock to the 30-minute expiry.
Route 57 at stop `RG:58617` went from a 115-second agency delay to the scheduled arrival.
After a simulated fresh publication the same stop again showed the 115-second delay. The test
polled local files every two seconds and made no upstream requests.

## Street closure

A synthetic WZDx report closed the vehicle carriageway on OSM way `22374856`, Horton Street in
Emeryville, from 08:20 to 09:00. The graph used real OSM geometry and three AC Transit routes from
the RG feed. Each run used the same captured regional transit updates. The origin was
`37.82970, -122.28770` and the destination was `37.83240, -122.28856`.

| Request                     | Baseline                   | Closure replay                                                |
| --------------------------- | -------------------------- | ------------------------------------------------------------- |
| Drive at 08:15              | 28 s via Horton            | 28 s via Horton                                               |
| Drive at 08:30              | Arrive 08:30:28 via Horton | Arrive 08:31:17 via Horton, Park, Hubbard, Sherwin and Horton |
| Drive to arrive at 08:30    | Depart 08:29:32            | Depart 08:28:43 via the same detour                           |
| Drive at 09:10              | 28 s via Horton            | 28 s via Horton                                               |
| Walk or wheelchair at 08:30 | 230 s                      | 230 s                                                         |
| Cycle at 08:30              | 63 s                       | 63 s                                                          |

The actual 08:56 regional road capture produced the baseline Horton itinerary. The 49-second detour
measures the effect of the synthetic report.

## Local routing performance

OTP's SpeedTest ran five transit requests on the complete November 24 RG timetable and the
Oakland/Emeryville street extract, using graph serialization version 293. Four requests used
the stop pairs in the regional transit table above. They comprised the J at 08:58, the 57 at
09:09 from `RG:51163`, and the 96/40 both departing at 09:11:20 and arriving by 09:58.
The fifth request started on Horton Street at `37.83240, -122.28856` at 08:58 and reached
`RG:50019` by walking to the F bus. Times are Pacific.

Both configurations used scheduled transit. One had no road updater and the other loaded the
08:56 traffic capture. All five cases returned transit journeys. The 12 selected itineraries
had identical output CSVs, including their routes, stops, times and costs.

| Median of five-request sample means | Scheduled RG | Scheduled RG with captured road traffic |
| --- | ---: | ---: |
| End-to-end routing | 28 ms | 25 ms |
| RAPTOR worker | 8.5 ms | 8 ms |

Each configuration ran in three fresh Java 25 processes with four processors available and a
4 GB maximum heap. Each process used SpeedTest options `-p md -n 4 -i 3 -0`. Timings use the
last two samples per process, after warm-up and replay initialization. The end-to-end sample
means ranged from 21–34 ms for the schedule and 24–28 ms with road traffic. The overlapping
ranges and small local workload limit performance conclusions. The repository's CI benchmark
uses larger, fixed datasets. [OTP performance benchmark](https://github.com/opentripplanner/OpenTripPlanner/blob/f95a1b72bf/test/performance/README.md)

## Oakland I-580 west to I-80 east

The Oakland graph includes the fork on westbound I-580 at OSM node `99569399`.
Its Berkeley branch, way `123867375`, carries the destination reference `I 580 West;I 80 East`.
The other branch, way `119058993`, leads toward San Francisco. A synthetic WZDx fixture
closes part of the Berkeley branch from November 24 at 23:00 to November 25 at 05:30.
The fixture uses November 2026 dates and the overnight hours from the April 2023 Caltrans
announcement. Geometry © OpenStreetMap contributors. [OSM way](https://www.openstreetmap.org/way/123867375) · [Caltrans closure notice](https://dot.ca.gov/caltrans-near-me/district-4/d4-news/2023-04-26-wb580-eb80-interchange-closure)

The test fixture is `application/src/ext-test/resources/org/opentripplanner/ext/traffic511/oakland-ramp-wzdx.json`.

OTP's car street search from the fork to OSM node `30362587` on eastbound I-80 took
57 seconds on the baseline graph. The replay found the same 57-second trip at 22:30
and at 05:35, in both search directions. At 00:15 the closure removed the through trip.
Of the graph's 2,281 edges, only the Berkeley branch matched the closure. Splitting the
branch downstream of the work at `37.8268160, -122.2888810` left the remaining section
open for a driver starting there. At 05:30 exactly, a car could depart the fork for I-80.
An arrive-by trip at the same instant would have needed to use the ramp before it reopened.

The available extract yielded no alternate through route from the fork during the closure.
Caltrans' temporary detour directed traffic through the toll-plaza parking lot under
officer and sign control. Reproducing that detour requires a verified, legally traversable
connection for its operating hours.

## Richmond–San Rafael shared-use path

Two WZDx features in the 08:56 capture named the Richmond–San Rafael Bridge in their
cross-street fields. They reported partial or unknown vehicle impact on I-580 and supplied
no lane or shared-use path identifier. WZDx 4.1 defines sidewalk and bike-lane types, and RG
provides transit service and alerts. A confirmed closure of the bridge's shared-use path
requires separate facility evidence. The published bridge guide links closure notices and
transit options. [511 bridge guide](https://511.org/travel/biking-walking/access/bridges) · [WZDx 4.1 lane types](https://raw.githubusercontent.com/usdot-jpo-ode/wzdx/main/schemas/4.1/RoadEventFeature.json)

## Evaluation status

The RG comparisons exercise the normal trip-planning and stop-time APIs. Road-based bus arrival
estimates are evaluated offline. Qualifying a corridor requires earlier training captures and
independent, date-matched observations. Regionwide accuracy, the signed interchange detour,
the shared-use bridge crossing and deployment acceptance remain open.

[Operator and replay instructions](Traffic511.md)
