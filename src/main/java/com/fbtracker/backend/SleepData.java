package com.fbtracker.backend;

/**
 * Normalized sleep data for a day. Either component may be null when the
 * provider response omits that section (mirrors the legacy conditional writes).
 *
 * @param mainSleep the primary sleep session, or null if none
 * @param summary   the day's sleep summary, or null if absent
 */
public record SleepData(MainSleep mainSleep, SleepSummary summary) {

    public record MainSleep(double minutesAsleep, int efficiency, int minutesToFallAsleep) {}

    /** stages may be null when the provider omits stage breakdown. */
    public record SleepSummary(SleepStages stages, double totalMinutesAsleep, int totalSleepRecords) {}

    public record SleepStages(int deep, int light, int rem, int wake) {}
}
