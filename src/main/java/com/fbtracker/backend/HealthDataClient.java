package com.fbtracker.backend;

import java.time.LocalDate;
import java.util.List;

/**
 * Provider-agnostic source of health metrics, consumed by {@link SyncService}.
 * Implementations adapt a specific backend (Fitbit Web API, Google Health API)
 * into normalized DTOs so the sync/write logic is independent of response shape.
 *
 * @see FitbitHealthDataClient
 */
public interface HealthDataClient {

    /**
     * Intraday samples for an activity metric (steps, calories, distance) on a day.
     * Returns an empty list when no data is available.
     */
    List<IntradayPoint> fetchActivityIntraday(String activity, LocalDate date);

    /** Heart-rate data (resting + intraday) for a day. */
    HeartRateData fetchHeartRate(LocalDate date);

    /** Oxygen-saturation (SpO2) data for a day. */
    OxygenData fetchOxygen(LocalDate date);

    /** Sleep data for a day. */
    SleepData fetchSleep(LocalDate date);
}
