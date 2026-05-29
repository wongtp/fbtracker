package com.fbtracker.backend;

import java.util.List;

/**
 * Normalized heart-rate data for a day.
 *
 * @param restingHeartRate resting BPM for the day, or null if unavailable/zero
 * @param intraday         intraday BPM samples (never null; may be empty)
 */
public record HeartRateData(Double restingHeartRate, List<IntradayPoint> intraday) {}
