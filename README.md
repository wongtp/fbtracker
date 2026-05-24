# fbtracker

![CI](https://github.com/wongtp/fbtracker/actions/workflows/ci.yml/badge.svg)

A self-hosted Fitbit health analytics service. Syncs your Fitbit data hourly to InfluxDB and surfaces it through Grafana dashboards.

## What it does

- Pulls **intraday (per-minute) data** for steps, calories, distance, and heart rate from the Fitbit Web API
- Pulls **daily summaries** for sleep stages, SpO2, and resting heart rate
- Persists OAuth tokens in PostgreSQL with automatic refresh-token rotation
- Writes time-series data to InfluxDB 2 with timezone-aware timestamps
- Visualizes everything through Grafana with Flux queries (cumulative daily progress, multi-day trends, etc.)
- Runs as a containerized Spring Boot service with all dependencies via Docker Compose

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
- Java 21 (the included Maven wrapper handles Maven itself)
- A registered [Fitbit Developer application](https://dev.fitbit.com/apps/new) with:
  - Application type: **Personal** (required for intraday data)
  - OAuth 2.0 Application Type: **Server**
  - Callback URL: `http://localhost:8080/callback`

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
```

### 3. Start the infra containers

```bash
docker compose up -d
```

This brings up PostgreSQL (port 5432), InfluxDB (port 8086), and Grafana (port 3000).

### 4. Run the app

```bash
./mvnw spring-boot:run
```

### 5. Authorize with Fitbit

Open http://localhost:8080/oauth2/authorization/fitbit, log in to Fitbit, and authorize the app. You'll be redirected back to localhost — that's the OAuth callback storing your access token in Postgres.

### 6. Wait or trigger a sync

Syncs run hourly on the cron schedule. To verify it's working, watch the logs for sync completion messages, or jump straight to Grafana.

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

**Today's cumulative step count** (since midnight in EST/EDT):

```flux
import "timezone"
option location = timezone.location(name: "America/New_York")

from(bucket: "health")
  |> range(start: today(), stop: now())
  |> filter(fn: (r) => r._measurement == "steps_intraday")
  |> filter(fn: (r) => r._field == "count")
  |> cumulativeSum(columns: ["_value"])
```

**Daily step totals over time** (bar chart):

```flux
from(bucket: "health")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "steps_intraday")
  |> filter(fn: (r) => r._field == "count")
  |> aggregateWindow(every: 1d, fn: sum, createEmpty: false)
```

## Project status

Personal learning project. What's done:

- Hourly sync of all listed metrics
- OAuth flow with automatic token refresh
- Postgres + InfluxDB containerized
- Grafana dashboards with intraday + daily aggregations
- Unit tests covering token refresh, 401 retry, and sync failure isolation
- CI via GitHub Actions

Roadmap:

- [ ] Historical backfill (years of past Fitbit data)
- [ ] Discord webhook for alerts (sync failures, daily summary, milestones)
- [ ] Deploy to home Mac mini for 24/7 operation
- [ ] Active zone minutes + HRV tracking
- [ ] Integration tests with Testcontainers (real Postgres in test runs)

## License

MIT
