package com.fbtracker.backend;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.fbtracker.backend.GoogleHealthApiClient.TimeAxis;

/**
 * {@link HealthDataClient} backed by the Google Health API. Owns the
 * Google-specific data-type identifiers and response shapes; delegates HTTP/token
 * concerns to {@link GoogleHealthApiClient}. Active only under the {@code google}
 * profile, where it replaces {@link FitbitHealthDataClient}.
 *
 * <p>Response shapes confirmed via live probes (see MIGRATION.md):
 * <ul>
 *   <li>value object nested under the camelCase data-type key (e.g. {@code point.heartRate}).</li>
 *   <li>numeric values are JSON <em>strings</em> ("64") → parse, don't cast.</li>
 *   <li>timestamps are UTC ISO instants → {@link Instant#parse} (no zone math).</li>
 *   <li>interval types expose {@code interval.startTime}; sample types {@code sampleTime.physicalTime}.</li>
 * </ul>
 */
@Service
@Profile("google")
public class GoogleHealthDataClient implements HealthDataClient {
    private static final Logger log = LoggerFactory.getLogger(GoogleHealthDataClient.class);

    /**
     * SpO2 samples at or below this percentage are treated as invalid sentinels and
     * excluded from the daily average. The Google Health API returns frequent exact
     * "50" placeholder readings interleaved with real values (see MIGRATION.md).
     */
    private static final double MIN_VALID_SPO2 = 50;

    /** Google reports distance in millimeters; the dashboard + legacy series are in miles. */
    private static final double MM_TO_MILES = 1.0 / 1_609_344;

    private final GoogleHealthApiClient apiClient;
    private final ZoneId zoneId;

    public GoogleHealthDataClient(GoogleHealthApiClient apiClient,
                                  @Value("${app.timezone}") String timezone) {
        this.apiClient = apiClient;
        this.zoneId = ZoneId.of(timezone);
    }

    @Override
    public List<IntradayPoint> fetchActivityIntraday(String activity, LocalDate date) {
        // All three use the de-duplicated daily rollup. The list endpoint returns multiple overlapping
        // data sources (e.g. Charge 6 + MobileTrack), so summing it double-counts; dailyRollUp is
        // Google's merged total and matches what the Google Health app shows.
        switch (activity) {
            case "steps":
                return dailyTotal("steps", "steps", "countSum", 1.0, date);
            case "distance":
                // millimetersSum scaled to miles to match the dashboard + legacy series.
                return dailyTotal("distance", "distance", "millimetersSum", MM_TO_MILES, date);
            case "calories":
                return dailyTotal("total-calories", "totalCalories", "kcalSum", 1.0, date);
            default:
                log.warn("Unknown activity '{}' for Google Health API", activity);
                return List.of();
        }
    }

    /**
     * Daily de-duplicated total for an activity metric via dailyRollUp, emitted as a single point at
     * local midnight. Used for steps/distance/calories: the list endpoint returns multiple overlapping
     * sources (Charge 6 + MobileTrack) that double-count when summed, whereas dailyRollUp is Google's
     * merged total. The dashboard aggregates these categories with SUM, so one point/day = the total.
     */
    @SuppressWarnings("unchecked")
    private List<IntradayPoint> dailyTotal(String dataType, String innerKey, String valueField, double scale, LocalDate date) {
        for (Map<String, Object> rollup : apiClient.dailyRollUp(dataType, date)) {
            var typed = (Map<String, Object>) rollup.get(innerKey);
            if (typed != null && typed.get(valueField) != null) {
                Instant timestamp = date.atStartOfDay(zoneId).toInstant();
                return List.of(new IntradayPoint(timestamp, num(typed.get(valueField)) * scale));
            }
        }
        return List.of();
    }

    @Override
    public HeartRateData fetchHeartRate(LocalDate date) {
        List<IntradayPoint> intraday = samplePoints("heart-rate", "heartRate", "beatsPerMinute", 1.0, date);
        return new HeartRateData(fetchRestingHeartRate(date), intraday);
    }

    /**
     * Resting HR is a separate daily data type (not in the heart-rate response). Scans the recent
     * daily-resting-heart-rate points for the target calendar date. NOTE: only the first page
     * (recent days) is checked — backfilling older dates needs the date filter (see MIGRATION.md).
     */
    @SuppressWarnings("unchecked")
    private Double fetchRestingHeartRate(LocalDate date) {
        for (Map<String, Object> point : apiClient.listRecent("daily-resting-heart-rate")) {
            var drhr = (Map<String, Object>) point.get("dailyRestingHeartRate");
            if (drhr == null) {
                continue;
            }
            var d = (Map<String, Object>) drhr.get("date");
            if (d != null && matchesDate(d, date) && drhr.get("beatsPerMinute") != null) {
                double bpm = num(drhr.get("beatsPerMinute"));
                return bpm > 0 ? bpm : null;
            }
        }
        return null;
    }

    private static boolean matchesDate(Map<String, Object> d, LocalDate date) {
        return (int) num(d.get("year")) == date.getYear()
            && (int) num(d.get("month")) == date.getMonthValue()
            && (int) num(d.get("day")) == date.getDayOfMonth();
    }

    @Override
    @SuppressWarnings("unchecked")
    public OxygenData fetchOxygen(LocalDate date) {
        List<Map<String, Object>> raw = apiClient.listDataPoints("oxygen-saturation", date, zoneId, TimeAxis.SAMPLE);
        double sum = 0;
        int n = 0;
        for (Map<String, Object> point : raw) {
            var os = (Map<String, Object>) point.get("oxygenSaturation");
            if (os == null || os.get("percentage") == null) {
                continue;
            }
            double pct = num(os.get("percentage"));
            if (pct <= MIN_VALID_SPO2) {
                continue; // sentinel/invalid placeholder, skip
            }
            sum += pct;
            n++;
        }
        // No daily aggregate endpoint for SpO2; average valid samples to match the legacy 'avg'.
        return n == 0 ? new OxygenData(null) : new OxygenData(sum / n);
    }

    @Override
    @SuppressWarnings("unchecked")
    public SleepData fetchSleep(LocalDate date) {
        List<Map<String, Object>> raw = apiClient.listDataPoints("sleep", date, zoneId, TimeAxis.SLEEP);
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (Map<String, Object> point : raw) {
            var s = (Map<String, Object>) point.get("sleep");
            if (s != null) {
                sessions.add(s);
            }
        }
        if (sessions.isEmpty()) {
            return new SleepData(null, null);
        }

        // No isMainSleep flag in Google → main session is the longest by minutesAsleep.
        // Stages (deep/light/rem/wake) are summed across all sessions to match the legacy
        // day-level sleep_stages measurement.
        Map<String, Object> mainSession = null;
        double bestAsleep = -1;
        double totalMinutesAsleep = 0;
        int deep = 0, light = 0, rem = 0, wake = 0;
        for (Map<String, Object> s : sessions) {
            var summary = (Map<String, Object>) s.get("summary");
            if (summary == null) {
                continue;
            }
            double asleep = num(summary.get("minutesAsleep"));
            totalMinutesAsleep += asleep;
            if (asleep > bestAsleep) {
                bestAsleep = asleep;
                mainSession = s;
            }
            var stagesSummary = (List<Map<String, Object>>) summary.get("stagesSummary");
            if (stagesSummary != null) {
                for (Map<String, Object> stage : stagesSummary) {
                    int minutes = (int) num(stage.get("minutes"));
                    switch (String.valueOf(stage.get("type"))) {
                        case "DEEP" -> deep += minutes;
                        case "LIGHT" -> light += minutes;
                        case "REM" -> rem += minutes;
                        case "AWAKE" -> wake += minutes;
                        default -> { /* unknown stage type, ignore */ }
                    }
                }
            }
        }

        SleepData.MainSleep mainSleep = null;
        if (mainSession != null) {
            var summary = (Map<String, Object>) mainSession.get("summary");
            double minutesAsleep = num(summary.get("minutesAsleep"));
            double minutesInPeriod = num(summary.get("minutesInSleepPeriod"));
            int minutesToFallAsleep = (int) num(summary.get("minutesToFallAsleep"));
            // Google has no efficiency field; approximate as asleep/time-in-period (see MIGRATION.md).
            int efficiency = minutesInPeriod > 0 ? (int) Math.round(minutesAsleep / minutesInPeriod * 100) : 0;
            mainSleep = new SleepData.MainSleep(minutesAsleep, efficiency, minutesToFallAsleep);
        }

        var stages = new SleepData.SleepStages(deep, light, rem, wake);
        var summary = new SleepData.SleepSummary(stages, totalMinutesAsleep, sessions.size());
        return new SleepData(mainSleep, summary);
    }

    /** Parse a Google Health numeric value, which is delivered as a JSON string. */
    private static double num(Object value) {
        return value == null ? 0 : Double.parseDouble(value.toString());
    }

    // ---- parsing helpers ----

    /** Sample-typed data points (e.g. heart-rate): timestamp from {@code sampleTime.physicalTime}. */
    private List<IntradayPoint> samplePoints(String dataType, String innerKey, String valueField,
                                             double scale, LocalDate date) {
        return parsePoints(dataType, innerKey, valueField, scale, TimeAxis.SAMPLE, date);
    }

    @SuppressWarnings("unchecked")
    private List<IntradayPoint> parsePoints(String dataType, String innerKey, String valueField,
                                            double scale, TimeAxis axis, LocalDate date) {
        List<Map<String, Object>> raw = apiClient.listDataPoints(dataType, date, zoneId, axis);
        List<IntradayPoint> points = new ArrayList<>(raw.size());
        for (Map<String, Object> point : raw) {
            var typed = (Map<String, Object>) point.get(innerKey);
            if (typed == null) {
                continue;
            }
            var timeObj = (Map<String, Object>) typed.get(axis.timeObjKey);
            Object value = typed.get(valueField);
            if (timeObj == null || timeObj.get(axis.timeField) == null || value == null) {
                continue;
            }
            Instant timestamp = Instant.parse(timeObj.get(axis.timeField).toString());
            points.add(new IntradayPoint(timestamp, num(value) * scale));
        }
        return points;
    }
}
