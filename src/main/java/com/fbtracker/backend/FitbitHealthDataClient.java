package com.fbtracker.backend;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * {@link HealthDataClient} backed by the legacy Fitbit Web API. Owns the
 * Fitbit-specific endpoint paths and JSON response shapes (parsing logic that
 * previously lived inline in {@link SyncService}); delegates HTTP/token concerns
 * to {@link FitbitApiClient}.
 *
 * <p>Default implementation. When the {@code google} profile is active, the
 * Google Health-backed implementation takes over (P3, see MIGRATION.md).
 */
@Service
@Profile("!google")
public class FitbitHealthDataClient implements HealthDataClient {
    private static final Logger log = LoggerFactory.getLogger(FitbitHealthDataClient.class);

    private final FitbitApiClient fitbitApiClient;
    private final ZoneId zoneId;

    public FitbitHealthDataClient(FitbitApiClient fitbitApiClient,
                                  @Value("${app.timezone}") String timezone) {
        this.fitbitApiClient = fitbitApiClient;
        this.zoneId = ZoneId.of(timezone);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<IntradayPoint> fetchActivityIntraday(String activity, LocalDate date) {
        var data = fitbitApiClient.fetchData(
            "/1/user/-/activities/" + activity + "/date/" + date + "/1d/1min.json");
        var resultsIntraday = (Map<String, Object>) data.get("activities-" + activity + "-intraday");
        if (resultsIntraday == null) {
            log.warn("No 'activities-{}-intraday' field in response. Response keys: {}", activity, data.keySet());
            return List.of();
        }
        var dataList = (List<Map<String, Object>>) resultsIntraday.get("dataset");
        if (dataList == null || dataList.isEmpty()) {
            return List.of();
        }
        return toIntradayPoints(dataList, date);
    }

    @Override
    @SuppressWarnings("unchecked")
    public HeartRateData fetchHeartRate(LocalDate date) {
        var data = fitbitApiClient.fetchData("/1/user/-/activities/heart/date/" + date + "/1d/1min.json");

        Double resting = null;
        var heartList = (List<Map<String, Object>>) data.get("activities-heart");
        if (heartList != null && !heartList.isEmpty()) {
            var value = (Map<String, Object>) heartList.get(0).get("value");
            if (value != null && value.get("restingHeartRate") != null) {
                double r = ((Number) value.get("restingHeartRate")).doubleValue();
                if (r > 0) {
                    resting = r;
                }
            }
        }

        List<IntradayPoint> intraday;
        var heartIntraday = (Map<String, Object>) data.get("activities-heart-intraday");
        if (heartIntraday == null) {
            log.warn("No 'activities-heart-intraday' field in response. Response keys: {}", data.keySet());
            intraday = List.of();
        } else {
            var heartData = (List<Map<String, Object>>) heartIntraday.get("dataset");
            intraday = (heartData == null || heartData.isEmpty()) ? List.of() : toIntradayPoints(heartData, date);
        }
        return new HeartRateData(resting, intraday);
    }

    @Override
    @SuppressWarnings("unchecked")
    public OxygenData fetchOxygen(LocalDate date) {
        var data = fitbitApiClient.fetchData("/1/user/-/spo2/date/" + date + ".json");
        var oxygen = (Map<String, Object>) data.get("value");
        if (oxygen == null || oxygen.get("avg") == null) {
            return new OxygenData(null);
        }
        return new OxygenData(((Number) oxygen.get("avg")).doubleValue());
    }

    @Override
    @SuppressWarnings("unchecked")
    public SleepData fetchSleep(LocalDate date) {
        var data = fitbitApiClient.fetchData("/1/user/-/sleep/date/" + date + ".json");

        SleepData.MainSleep mainSleep = null;
        var sleepData = (List<Map<String, Object>>) data.get("sleep");
        if (sleepData != null && !sleepData.isEmpty()) {
            Map<String, Object> ms = sleepData.stream()
                .filter(s -> Boolean.TRUE.equals(s.get("isMainSleep")))
                .findFirst()
                .orElse(null);
            if (ms != null) {
                mainSleep = new SleepData.MainSleep(
                    ((Number) ms.get("minutesAsleep")).doubleValue(),
                    ((Number) ms.get("efficiency")).intValue(),
                    ((Number) ms.get("minutesToFallAsleep")).intValue());
            }
        }

        SleepData.SleepSummary summary = null;
        var sleepSummary = (Map<String, Object>) data.get("summary");
        if (sleepSummary != null) {
            SleepData.SleepStages stages = null;
            var st = (Map<String, Object>) sleepSummary.get("stages");
            if (st != null) {
                stages = new SleepData.SleepStages(
                    ((Number) st.get("deep")).intValue(),
                    ((Number) st.get("light")).intValue(),
                    ((Number) st.get("rem")).intValue(),
                    ((Number) st.get("wake")).intValue());
            }
            summary = new SleepData.SleepSummary(
                stages,
                ((Number) sleepSummary.get("totalMinutesAsleep")).doubleValue(),
                ((Number) sleepSummary.get("totalSleepRecords")).intValue());
        }
        return new SleepData(mainSleep, summary);
    }

    /** Resolve Fitbit's local {@code HH:mm:ss} times against the configured zone into instants. */
    private List<IntradayPoint> toIntradayPoints(List<Map<String, Object>> dataset, LocalDate date) {
        List<IntradayPoint> points = new ArrayList<>(dataset.size());
        for (Map<String, Object> entry : dataset) {
            double value = ((Number) entry.get("value")).doubleValue();
            var timestamp = date.atTime(LocalTime.parse(entry.get("time").toString())).atZone(zoneId).toInstant();
            points.add(new IntradayPoint(timestamp, value));
        }
        return points;
    }
}
