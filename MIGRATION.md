# Fitbit Web API → Google Health API Migration

Tracking doc for migrating fbtracker off the legacy Fitbit Web API
(`api.fitbit.com`) onto the **Google Health API** (`health.googleapis.com/v4`).

## Why / deadline

- **Sept 2026**: legacy Fitbit Web API is turned down. After this date
  `api.fitbit.com` stops serving data and the current app stops working.
- **End of May 2026**: Google Health API is GA; recommended window to build/launch.

Source: [Fitbit Web API next phase](https://community.fitbit.com/t5/Web-API-Development/Introducing-the-next-phase-of-the-Fitbit-Web-API/td-p/5821061),
[Google Health migration guide](https://developers.google.com/health/migration).

## What changes

| Concern | Legacy (today) | Google Health API |
| --- | --- | --- |
| Base URL | `https://api.fitbit.com` | `https://health.googleapis.com/v4` |
| Auth | Fitbit OAuth 2.0 | Google OAuth 2.0 (`accounts.google.com` / `oauth2.googleapis.com`) |
| Authz URL | `www.fitbit.com/oauth2/authorize` | `accounts.google.com/o/oauth2/v2/auth` |
| Token URL | `api.fitbit.com/oauth2/token` | `oauth2.googleapis.com/token` |
| Scopes | comma list (`activity,heartrate,...`) | full URLs (`https://www.googleapis.com/auth/googlehealth.*`) |
| Request model | date in URL path, `.json` suffix | `dataTypes/{type}/dataPoints` + `filter` query / POST rollup body |
| Pagination | none | `nextPageToken` |
| Response | `activities-X-intraday.dataset[{time,value}]` | `dataPoints[].{type}.{...}` |
| Tokens | — | **cannot be carried over; must re-consent once** |

## Scopes needed

- `https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly` — steps, distance, heart rate, calories
- `https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly` — oxygen saturation, resting HR
- `https://www.googleapis.com/auth/googlehealth.sleep.readonly` — sleep
- (standard OIDC `openid`/`profile` for login identity)

## Endpoint / data-type mapping

Current app pulls 4 things in [SyncService.java](src/main/java/com/fbtracker/backend/SyncService.java).
New data type identifiers are kebab-case in the URL path.

| App metric | Legacy endpoint | New data type | New method | Intraday? |
| --- | --- | --- | --- | --- |
| steps | `/1/user/-/activities/steps/date/{d}/1d/1min.json` | `steps` | `list` (GET) | ✅ yes |
| distance | `/1/user/-/activities/distance/date/{d}/1d/1min.json` | `distance` | `list` (GET) | ✅ yes |
| calories | `/1/user/-/activities/calories/date/{d}/1d/1min.json` | `total-calories` | `dailyRollUp` (POST) | ❌ **no intraday** |
| heart rate (intraday) | `/1/user/-/activities/heart/date/{d}/1d/1min.json` | `heart-rate` | `list` (GET) | ✅ yes (~5s samples) |
| resting heart rate | (embedded in heart response above) | `daily-resting-heart-rate` | `dailyRollUp` / `list` | ❌ daily only |
| oxygen / SpO2 | `/1/user/-/spo2/date/{d}.json` | `oxygen-saturation` | `list` / `reconcile` | partial |
| sleep | `/1/user/-/sleep/date/{d}.json` | `sleep` | `list` (session) | ✅ |

### REST shapes

List (intraday):
```
GET https://health.googleapis.com/v4/users/me/dataTypes/{type}/dataPoints?filter=...civil_start_time >= "2026-03-04T00:00:00"
```
Response:
```json
{ "dataPoints": [ { "dataSource": {...}, "steps": { "interval": {"startTime","endTime"}, "count": "40" } } ], "nextPageToken": "..." }
```

dailyRollUp (daily aggregate):
```
POST https://health.googleapis.com/v4/users/me/dataTypes/{type}/dataPoints:dailyRollUp
{ "range": { "start": {"date":{...},"time":{...}}, "end": {...} }, "windowSizeDays": 1 }
```

## ⚠️ Behavioral changes that affect our data model

1. **Calories loses minute-level intraday.** `total-calories` only supports rollup/dailyRollUp.
   RESOLVED: fetch the daily total via `dailyRollUp` and write it as **one point/day** to
   `calories_intraday`/`count`; the dashboard aggregates that category with SUM, so the daily total
   and historical continuity both hold. ⚠️ `total-calories` is *total* burn (incl. BMR) vs the old
   "Active Calories" label — numbers will read higher; relabel or subtract a BMR baseline if needed.
2. **Resting HR is a separate daily data type** (`daily-resting-heart-rate`) instead of being
   embedded in the heart response. The `heartrate.resting` write needs a second call.
3. **Pagination**: a day of `heart-rate` is ~8,700 samples → must follow `nextPageToken`.
4. **Response parsing is a full rewrite**, not a URL swap. New nested/typed shape per data type.
5. **Rate limits / pacing** differ from Fitbit's 150/hr — [BackfillService](src/main/java/com/fbtracker/backend/BackfillService.java)
   `REQUEST_PACING_MS` must be re-tuned once Google's quota is known.

## Confirmed response shapes (from live probes)

### steps (verified 2026-05-29)
```json
{ "nextPageToken": "...", "dataPoints": [ {
  "steps": { "count": "11",
             "interval": { "startTime": "2026-05-29T17:34:00Z", "endTime": "...Z",
                           "startUtcOffset": "-14400s",
                           "civilStartTime": {"date":{year,month,day},"time":{hours,minutes}}, ... } },
  "dataSource": { "device": {"displayName":"Charge 6"}, "recordingMethod": "PASSIVELY_MEASURED", "platform": "FITBIT" }
} ] }
```
Parsing rules locked in:
- value = `Double.parseDouble(point.{type}.count)` — **count is a STRING**, not a number.
- timestamp = `Instant.parse(point.{type}.interval.startTime)` — already UTC, **no zone conversion** (unlike Fitbit).
- the value object is nested under the data-type key (`point.steps`), so each type has its own inner shape.

### heart-rate (verified 2026-05-29)
```json
{ "dataPoints": [ {
  "heartRate": { "beatsPerMinute": "64",
                 "sampleTime": { "physicalTime": "2026-05-29T18:51:19Z", "utcOffset": "-14400s",
                                 "civilTime": {"date":{...},"time":{hours,minutes,seconds}} } },
  "dataSource": {...} } ] }
```
- inner key = **camelCase** of the data type (`heart-rate` → `heartRate`, `steps` → `steps`).
- value = `Double.parseDouble(point.heartRate.beatsPerMinute)` — string again.
- timestamp = `Instant.parse(point.heartRate.sampleTime.physicalTime)` — **Sample type uses `sampleTime.physicalTime`**, not `interval.startTime`. Two strategies needed.
- **No restingHeartRate in this response** → resting HR must come from the separate `daily-resting-heart-rate` type (one extra call/day). Confirmed.

### sleep (verified 2026-05-29) — Session type
```json
{ "dataPoints": [ {
  "sleep": {
    "interval": { "startTime": "...Z", "endTime": "...Z", ... },
    "summary": { "minutesAsleep": "366", "minutesInSleepPeriod": "377", "minutesAwake": "11",
                 "minutesToFallAsleep": "0", "minutesAfterWakeUp": "0",
                 "stagesSummary": [ {"type":"AWAKE","minutes":"10","count":"2"},
                                    {"type":"LIGHT","minutes":"194",...},
                                    {"type":"DEEP",...}, {"type":"REM",...} ] },
    "stages": [ {"type":"LIGHT","startTime":"...","endTime":"..."}, ... ],   // per-stage intervals
    "type": "STAGES", "metadata": {"stagesStatus":"SUCCEEDED"} },
  "dataSource": {...} } ] }
```
Mapping decisions (deviations from Fitbit flagged):
- one dataPoint **per sleep session**; multiple per query (overnight + naps).
- main session = longest by `minutesAsleep` (no `isMainSleep` flag in Google).
- **`efficiency` does not exist** → approximated as `round(minutesAsleep / minutesInSleepPeriod * 100)`.
  Differs from Fitbit's proprietary efficiency → small discontinuity in the stored `sleep.efficiency` series.
- stages (deep/light/rem/wake) summed across the date's sessions from `stagesSummary` (`AWAKE`→wake), matching the old day-level `sleep_stages`.
- `totalMinutesAsleep` = sum of session `minutesAsleep`; `totalSleepRecords` = session count.
- ⚠️ wake-date attribution: a session starting before midnight has `civil_start_time` on the prior day; needs handling in the date filter (see filter-verification step) so it lands on the correct day like Fitbit's `/sleep/date`.

### oxygen-saturation (verified 2026-05-29) — Sample type
```json
{ "dataPoints": [ {
  "oxygenSaturation": { "percentage": 95.2,
                        "sampleTime": { "physicalTime": "2026-05-29T13:03:03Z", ... } },
  "dataSource": {...} } ] }
```
- inner key `oxygenSaturation`; value `percentage` is a JSON **number** (not string).
- ⚠️ **No daily avg** (legacy `/spo2/date` gave `value.avg`); `oxygen-saturation` has no rollup, so
  we compute the day average client-side from samples.
- ⚠️ Samples include **sentinel junk** (many exact `50` values mixed with real 84–97% readings).
  We filter `percentage <= MIN_VALID_SPO2` (=50) before averaging — tunable constant in GoogleHealthDataClient.
  Also note: legacy avg was sleep-based; this is an all-day average → values will differ.

### distance (verified 2026-05-29) — interval type
```json
{ "dataPoints": [ {
  "distance": { "interval": { "startTime": "2026-05-29T19:04:00Z", ... }, "millimeters": "4200" },
  "dataSource": {...} } ] }
```
- inner key `distance`; value field **`millimeters`** (string), NOT `count`.
- ⚠️ unit change: Fitbit gave distance in account units; Google gives **mm**. The dashboard formats
  distance as **miles** and the legacy series is miles, so we convert mm→miles (`MM_TO_MILES = 1/1609344`).

### daily-resting-heart-rate (verified 2026-05-29) — Daily type
```json
{ "dataPoints": [ {
  "dailyRestingHeartRate": { "date": {"year":2026,"month":5,"day":29}, "beatsPerMinute": "65",
                             "dailyRestingHeartRateMetadata": {"calculationMethod":"WITH_SLEEP"} },
  "dataSource": {...} } ] }
```
- works via plain `list` (no dailyRollUp needed); newest-first, one per day.
- inner key `dailyRestingHeartRate`; value `beatsPerMinute` (string); date is a civil `date` object (no time).
- resting HR matched client-side by calendar date via `listRecent` (first page only → covers recent days;
  older backfill needs the verified date filter).

### date filter (verified 2026-05-29)
- conjunction must be **uppercase `AND`** (lowercase `and` → 400 "global restriction").
- interval types: `{type}.interval.civil_start_time >= "YYYY-MM-DDT00:00:00"` (local civil, no Z).
- sample types: `{type}.sample_time.physical_time >= "YYYY-MM-DDT04:00:00Z"` (UTC instants spanning the local day).
- both verified to return exactly one local day's data (checked unique `civilStartTime`/`civilTime` dates).
- ⚠️ list scans newest-first: a past-date query can return an **empty first page with a nextPageToken** —
  the pagination loop in listDataPoints is required (a single request would miss older days).
- ⚠️ do NOT hand-encode the filter and pass a pre-built URI string to RestClient — it double-encodes
  (`>=` → `%253E%253D`), and Google then sees no comparators ("global restriction" at every token).
  Pass the raw filter via the URI builder `queryParam` so it's encoded exactly once.

### total-calories dailyRollUp (verified 2026-05-29) — Daily aggregate (no intraday)
`POST /users/me/dataTypes/total-calories/dataPoints:dailyRollUp` with body
`{"range":{"start":{"date":{...},"time":{...}},"end":{...}},"windowSizeDays":1}`:
```json
{ "rollupDataPoints": [ {
  "civilStartTime": {"date":{...}}, "civilEndTime": {"date":{...}},
  "totalCalories": { "kcalSum": 2539.57 } } ] }
```
- envelope is `rollupDataPoints` (not `dataPoints`); value `totalCalories.kcalSum` (number).
- written as a single point at local midnight → SUM-aggregated by the dashboard.

### ⚠️ steps/distance: list endpoint double-counts (use dailyRollUp)
The `list` endpoint returns intraday points from **multiple overlapping data sources** — e.g.
`Charge 6` (watch) AND `MobileTrack` (phone) — at non-aligned minute intervals. Summing all of them
~doubles the daily total (5/29: list-sum 10370 / 6747 vs Google app 4966). The Google Health app shows
a *merged* total, which the API exposes via **`dailyRollUp`** (`steps.countSum`, `distance.millimetersSum`).
RESOLVED: steps, distance, and calories all use `dailyRollUp` → one merged daily point/day (matches the app;
dashboard SUM-aggregates). The `list`/intraday path is only used for heart-rate now (not dashboard-displayed).

## Open questions (verify against GA API before finishing)

- [ ] Exact value field names per data type (e.g. heart-rate sample `bpm`, oxygen-saturation `percentage`, sleep stage minutes). Docs didn't enumerate all fields.
- [ ] Does `daily-resting-heart-rate` come from `list` or only `dailyRollUp`?
- [ ] Confirm scope grouping for `heart-rate` (activity vs health-metrics bundle).
- [ ] Google Health API quota / rate limits for backfill pacing.
- [ ] Historical backfill depth available via the API.

## Phased plan

- [x] **P0** Branch `migrate-google-health-api`, research, this doc.
- [x] **P1** Google OAuth scaffolding behind `google` Spring profile (non-breaking).
      → [application-google.yml](src/main/resources/application-google.yml),
      [GoogleHealthProperties](src/main/java/com/fbtracker/backend/GoogleHealthProperties.java),
      [GoogleHealthApiConfig](src/main/java/com/fbtracker/backend/GoogleHealthApiConfig.java).
- [~] **P2** `GoogleHealthApiClient` with real endpoints, auth, pagination; parsing per data type.
      → [GoogleHealthApiClient](src/main/java/com/fbtracker/backend/GoogleHealthApiClient.java)
      scaffolded: `list` + pagination + Google token refresh done; per-data-type
      `dailyRollUp` and response→Influx parsing are TODO(P3) pending field-name confirmation.
- [x] **P3a** Semantic abstraction introduced. [HealthDataClient](src/main/java/com/fbtracker/backend/HealthDataClient.java)
      interface + normalized DTOs ([IntradayPoint](src/main/java/com/fbtracker/backend/IntradayPoint.java),
      [HeartRateData](src/main/java/com/fbtracker/backend/HeartRateData.java),
      [OxygenData](src/main/java/com/fbtracker/backend/OxygenData.java),
      [SleepData](src/main/java/com/fbtracker/backend/SleepData.java)).
      Fitbit JSON parsing moved out of SyncService into
      [FitbitHealthDataClient](src/main/java/com/fbtracker/backend/FitbitHealthDataClient.java);
      [SyncService](src/main/java/com/fbtracker/backend/SyncService.java) now depends on the
      interface. Behavior unchanged, all 16 tests green.
- [x] **P3b** [GoogleHealthDataClient](src/main/java/com/fbtracker/backend/GoogleHealthDataClient.java)
      implemented for all 6 metrics (`@Profile("google")`; Fitbit impl `@Profile("!google")`).
      All response shapes verified via live probes (see "Confirmed response shapes"). Build + 16 tests green.
- [x] **P3c** Date `filter` fixed (TimeAxis enum: interval/sample/sleep) and **verified live** —
      bounds to one local day; uppercase `AND`; pagination loop confirmed necessary. Sleep attributed by
      `civil_end_time` (wake date). Data layer is complete and verified.
- [ ] **P4** Token storage/refresh for Google ([TokenRefreshService](src/main/java/com/fbtracker/backend/TokenRefreshService.java)), re-tune backfill pacing.
- [x] **P5a** Google OAuth login wired (code complete, build green):
      [GoogleOAuthConfig](src/main/java/com/fbtracker/backend/GoogleOAuthConfig.java) adds
      `access_type=offline`+`prompt=consent` (refresh token); [SecurityConfig](src/main/java/com/fbtracker/backend/SecurityConfig.java)
      applies it under the `google` profile and stores the token (now null-safe on refresh token).
- [ ] **P5b** Live end-to-end test under `google` profile (needs :8080 freed from the Docker container):
      `SPRING_PROFILES_ACTIVE=google ./mvnw spring-boot:run` → log in via Google at `/` → confirm a
      refresh token is stored → `POST /admin/backfill?start=<today>&end=<today>` → verify data in Influx.
- [ ] **P5c** Cutover: make `google` the default, register prod redirect URIs, remove the Fitbit path
      (FitbitApiClient/FitbitHealthDataClient/Fitbit config) once validated.

## Dev testing (before OAuth login is wired)

The Google OAuth *login* flow isn't wired yet (SecurityConfig/FitbitOAuth2UserService
are Fitbit-specific — that's P5). To probe response shapes now:

1. Put creds in [.env](.env): `GOOGLE_HEALTH_CLIENT_ID`, `GOOGLE_HEALTH_CLIENT_SECRET`.
2. Run with the profile: `SPRING_PROFILES_ACTIVE=google ./mvnw spring-boot:run`
3. Mint a token at the [OAuth 2.0 Playground](https://developers.google.com/oauthplayground/):
   gear ▸ "Use your own OAuth credentials" (paste client id/secret), authorize the
   `googlehealth.*` scopes, exchange for an access token.
4. Probe via [GoogleHealthDevController](src/main/java/com/fbtracker/backend/GoogleHealthDevController.java):
   ```
   curl -H "Authorization: Bearer <token>" \
     "http://localhost:8080/dev/google-health/steps?date=2026-05-28"
   ```
   Returns the raw JSON envelope → use it to confirm field names and fill the parsing TODOs.

## Notes

The legacy Fitbit path stays fully intact and default throughout P1–P4. The Google
path is profile-gated (`SPRING_PROFILES_ACTIVE=google`) so the working app is never
broken while building. Cutover happens only at P5.
