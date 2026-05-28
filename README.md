# fbtracker

![CI](https://github.com/wongtp/fbtracker/actions/workflows/ci.yml/badge.svg)

A self-hosted Fitbit health analytics service. Syncs your Fitbit data hourly to InfluxDB and surfaces it through Grafana dashboards.

## What it does

- Pulls **intraday (per-minute) data** for steps, calories, distance, and heart rate from the Fitbit Web API
- Pulls **daily summaries** for sleep stages, SpO2, and resting heart rate
- Persists OAuth tokens in PostgreSQL with synchronized refresh-token rotation
- Writes time-series data to InfluxDB 2 with configurable, timezone-aware timestamps
- Visualizes everything through Grafana with Flux queries (cumulative daily progress, multi-day trends, etc.)
- Sends **daily summaries and token-refresh failure alerts** to Discord
- **Backfills historical Fitbit data** from a chosen date range, paced under Fitbit's rate limit, with a web UI to trigger and monitor jobs
- Runs entirely in Docker Compose — Spring Boot service, Postgres, InfluxDB, Grafana, all one command

## Tech stack

| Layer | Tool |
|---|---|
| Language / Runtime | Java 21 |
| Framework | Spring Boot 4 |
| Auth | Spring Security OAuth2 Client (Fitbit OAuth 2.0) |
| Time-series store | InfluxDB 2 |
| Relational store | PostgreSQL 16 (Spring Data JPA / Hibernate) |
| Visualization | Grafana + Flux |
| Infrastructure | Docker Compose |
| Tests | JUnit 5 + Mockito + AssertJ |
| CI | GitHub Actions |

## Architecture

```
              ┌────────────────┐
              │ Fitbit Web API │
              └────────┬───────┘
                       │ OAuth 2.0
                       ▼
        ┌───────────────────────────────┐
        │     Spring Boot service       │
        │  - Token refresh + 401 retry  │
        │  - Hourly scheduled syncs     │      ┌───────────┐
        │  - Per-metric handlers        ├─────▶│ Postgres  │  OAuth tokens
        │  - Failure isolation          │      └───────────┘
        └────────────┬──────────────────┘
                     │
                     ▼
              ┌──────────────┐               ┌──────────┐
              │  InfluxDB 2  │ ◀─────────────│ Grafana  │
              └──────────────┘   Flux query  └──────────┘
```

Why two databases? OAuth tokens are tiny relational records (rotate frequently, must be queried by recency) — PostgreSQL is the right fit. Health metrics are append-heavy time-series data — InfluxDB is purpose-built for that workload.

## Setup

### Prerequisites

- Docker + Docker Compose
- A registered [Fitbit Developer application](https://dev.fitbit.com/apps/new) with:
  - Application type: **Personal** (required for intraday data)
  - OAuth 2.0 Application Type: **Server**
  - Callback URL: `http://localhost:8080/callback`

Java 21 is only needed if you want to run the backend outside Docker (e.g. `./mvnw spring-boot:run` for local development).

### 1. Clone

```bash
git clone https://github.com/wongtp/fbtracker.git
cd fbtracker
```

### 2. Configure secrets

Create a `.env` file in the project root (gitignored):

```env
FITBIT_CLIENT_ID=your-client-id
FITBIT_CLIENT_SECRET=your-client-secret
INFLUX_TOKEN=any-token-you-want
POSTGRES_PASSWORD=any-password
DISCORD_WEBHOOK_URL=optional-discord-webhook-url
```

Optionally override the timezone for cron schedules and timestamp parsing (defaults to `America/New_York`):

```env
TZ=America/Los_Angeles
```

### 3. Start everything

```bash
docker compose up -d
```

This brings up the backend (port 8080), PostgreSQL (5432), InfluxDB (8086), and Grafana (3000). All services have `restart: unless-stopped`, so they come back automatically after a Docker Desktop or host restart.

### 4. Authorize with Fitbit

Open http://localhost:8080/oauth2/authorization/fitbit, log in to Fitbit, and authorize the app. You'll be redirected back to localhost — that's the OAuth callback storing your access token in Postgres.

### 5. Wait or trigger a sync

Syncs run hourly on a staggered cron schedule. To verify it's working, watch the logs for sync completion messages, or jump straight to Grafana.

### 6. Backfill historical data (optional)

Open http://localhost:8080/admin/index.html, pick a start and end date, and click **Start backfill**. The job runs asynchronously with rate-limit pacing (~2.5 min per day to stay under Fitbit's 150 req/hour limit), persists progress to Postgres, and auto-resumes if the container restarts mid-run. Days with no real activity are detected and skipped.

### 7. Set up Grafana

1. Open http://localhost:3000 (default credentials: `admin` / `admin`)
2. Add an InfluxDB data source:
   - URL: `http://influxdb2:8086`
   - Organization: `fbtracker`
   - Default bucket: `health`
   - Token: the value of `INFLUX_TOKEN` from your `.env`
3. Build dashboards with Flux queries — see [Sample queries](#sample-queries) below.

## Metrics tracked

| Influx measurement | Resolution | Source endpoint |
|---|---|---|
| `steps_intraday` | per-minute | `/1/user/-/activities/steps/date/today/1d/1min.json` |
| `calories_intraday` | per-minute | `/1/user/-/activities/calories/date/today/1d/1min.json` |
| `distance_intraday` | per-minute | `/1/user/-/activities/distance/date/today/1d/1min.json` |
| `heartrate_intraday` | per-minute | `/1/user/-/activities/heart/date/today/1d/1min.json` |
| `heartrate` | daily | resting heart rate from same endpoint |
| `oxygen` | daily | `/1/user/-/spo2/date/today.json` |
| `sleep`, `sleep_stages`, `sleep_summary` | daily | `/1/user/-/sleep/date/today.json` |

## Sample queries

**Today's cumulative step count** (since midnight, scoped to today):

```flux
import "timezone"
option location = timezone.location(name: "America/New_York")

from(bucket: "health")
  |> range(start: today(), stop: now())
  |> filter(fn: (r) => r._measurement == "steps_intraday")
  |> filter(fn: (r) => r._field == "count")
  |> cumulativeSum(columns: ["_value"])
```

**Daily step totals over time** (bar chart — use `v.timeRangeStart` / `v.timeRangeStop` so Grafana's time picker controls the range, otherwise historical data won't show up):

```flux
from(bucket: "health")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "steps_intraday")
  |> filter(fn: (r) => r._field == "count")
  |> aggregateWindow(every: 1d, fn: sum, createEmpty: false)
```

## Project status

Personal learning project. What's done:

- Hourly sync of all listed metrics, staggered across the hour to avoid Fitbit's per-client load-balancer throttling
- Sleep sync runs twice daily (10am and 1pm ET) instead of hourly, so sleep data isn't written 24 times per day
- OAuth flow with synchronized token refresh — multiple concurrent syncs can't race and burn refresh tokens
- Automatic retry-once on transient Fitbit 5xx errors, plus refresh-and-retry on 401
- Configurable timezone via single `TZ` env var (cron schedules, timestamp parsing, and Flux queries all use it)
- Fully containerized — Spring Boot service + Postgres + InfluxDB + Grafana, all with `restart: unless-stopped`
- Grafana dashboards with intraday + daily aggregations
- Discord notifications for daily summaries and token-refresh failures
- Historical backfill with a web admin UI (`/admin/index.html`): pick a date range, watch live progress, see errors. Async, rate-limit-paced, auto-resumes on container restart, idempotent on re-runs
- Pre-device-activation days (all-zero data from Fitbit) are detected and skipped
- Unit tests cover sync failure isolation, token refresh, 401 retry, 5xx retry, and Discord behavior
- CI via GitHub Actions

## License

MIT
