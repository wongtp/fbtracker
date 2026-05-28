package com.fbtracker.backend;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SyncService {
    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private final FitbitApiClient fitbitApiClient;
    private final InfluxWriteService influxWriteService;
    private final ZoneId zoneId;

    public SyncService(FitbitApiClient fitbitApiClient, InfluxWriteService influxWriteService,
                       @Value("${app.timezone}") String timezone){
        this.fitbitApiClient = fitbitApiClient;
        this.influxWriteService = influxWriteService;
        this.zoneId = ZoneId.of(timezone);
    }

    @Scheduled(cron = "0 0 * * * *", zone = "${app.timezone}")
    public void syncActivities() {
        syncActivitiesForDate(LocalDate.now(zoneId));
    }

    public void syncActivitiesForDate(LocalDate date) {
        activityHelper("steps", date);
        activityHelper("calories", date);
        activityHelper("distance", date);
    }

    private void activityHelper(String activity, LocalDate date) {
        try {
            var data = fitbitApiClient.fetchData("/1/user/-/activities/" + activity + "/date/" + date + "/1d/1min.json");
            var resultsIntraday = (Map<String, Object>) data.get("activities-" + activity+"-intraday");
            if (resultsIntraday == null) {
                log.warn("No 'activities-{}-intraday' field in response. Response keys: {}", activity, data.keySet());
                return;
            }
            var dataList = (List<Map<String, Object>>) resultsIntraday.get("dataset");
            if (dataList == null || dataList.isEmpty()) {
                log.warn("Empty intraday dataset for {} on {}", activity, date);
                return;
            }
            double dayTotal = dataList.stream().mapToDouble(e -> ((Number) e.get("value")).doubleValue()).sum();
            if (dayTotal == 0) {
                log.info("Skipping {} on {}: all-zero day (likely pre-device-activation)", activity, date);
                return;
            }
            log.info("Writing {} intraday datapoints for {} on {}", dataList.size(), activity, date);
            for (Map<String, Object> entry : dataList) {
                double count = ((Number) entry.get("value")).doubleValue();
                Instant timestamp = date.atTime(LocalTime.parse(entry.get("time").toString())).atZone(zoneId).toInstant();
                influxWriteService.writeData(activity+"_intraday", Map.of("source", "fitbit"), "count", count, timestamp);
            }
        } catch (Exception e) {
            log.error("Sync failed for {} on {}: {}", activity, date, e.getMessage());
        }
    }

    @Scheduled(cron = "0 5 * * * *", zone = "${app.timezone}")
    public void syncHeartrate() {
        syncHeartrateForDate(LocalDate.now(zoneId));
    }

    public void syncHeartrateForDate(LocalDate date) {
        try {
            var data = fitbitApiClient.fetchData("/1/user/-/activities/heart/date/" + date + "/1d/1min.json");
            Instant dayStart = date.atStartOfDay(zoneId).toInstant();

            var heartList = (List<Map<String, Object>>) data.get("activities-heart");
            if (heartList != null && !heartList.isEmpty()){
                var value = (Map<String, Object>) heartList.get(0).get("value");
                if (value != null && value.get("restingHeartRate") != null) {
                    double resting = ((Number) value.get("restingHeartRate")).doubleValue();
                    if (resting > 0) {
                        influxWriteService.writeData("heartrate", Map.of("source", "fitbit"), "resting", resting, dayStart);
                    }
                }
            }

            var heartIntraday = (Map<String, Object>) data.get("activities-heart-intraday");
            if (heartIntraday == null) {
                log.warn("No 'activities-heart-intraday' field in response. Response keys: {}", data.keySet());
            } else {
                var heartData = (List<Map<String, Object>>) heartIntraday.get("dataset");
                if (heartData == null || heartData.isEmpty()) {
                    log.warn("Empty intraday dataset for heartrate on {}", date);
                } else {
                    double dayTotal = heartData.stream().mapToDouble(e -> ((Number) e.get("value")).doubleValue()).sum();
                    if (dayTotal == 0) {
                        log.info("Skipping heartrate on {}: all-zero day (likely pre-device-activation)", date);
                    } else {
                        log.info("Writing {} intraday datapoints for heartrate on {}", heartData.size(), date);
                        for (Map<String, Object> entry : heartData) {
                            double bpm = ((Number) entry.get("value")).doubleValue();
                            Instant timestamp = date.atTime(LocalTime.parse(entry.get("time").toString())).atZone(zoneId).toInstant();
                            influxWriteService.writeData("heartrate_intraday", Map.of("source", "fitbit"), "bpm", bpm, timestamp);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Sync failed for heartrate on {}: {}", date, e.getMessage());
        }
    }

    @Scheduled(cron = "0 10 * * * *", zone = "${app.timezone}")
    public void syncOxygen() {
        syncOxygenForDate(LocalDate.now(zoneId));
    }

    public void syncOxygenForDate(LocalDate date) {
        try {
            var data = fitbitApiClient.fetchData("/1/user/-/spo2/date/" + date + ".json");
            var oxygen = (Map<String, Object>) data.get("value");
            if (oxygen == null) return;
            double avg = ((Number) oxygen.get("avg")).doubleValue();
            if (avg == 0) {
                log.info("Skipping oxygen on {}: zero value (likely pre-device-activation)", date);
                return;
            }
            Instant dayStart = date.atStartOfDay(zoneId).toInstant();
            influxWriteService.writeData("oxygen", Map.of("source", "fitbit"), "avg", avg, dayStart);
        } catch (Exception e) {
            log.error("Sync failed for oxygen on {}: {}", date, e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 10 * * *", zone = "${app.timezone}")
    @Scheduled(cron = "0 0 13 * * *", zone = "${app.timezone}")
    public void syncSleep() {
        syncSleepForDate(LocalDate.now(zoneId));
    }

    public void syncSleepForDate(LocalDate date) {
        try {
            var data = fitbitApiClient.fetchData("/1/user/-/sleep/date/" + date + ".json");
            Instant dayStart = date.atStartOfDay(zoneId).toInstant();

            var sleepData = (List<Map<String, Object>>) data.get("sleep");
            if (sleepData != null && !sleepData.isEmpty()){
                Map<String, Object> mainSleep = sleepData.stream()
                    .filter(s -> Boolean.TRUE.equals(s.get("isMainSleep")))
                    .findFirst()
                    .orElse(null);
                if (mainSleep != null){
                    double minutesAsleep = ((Number) mainSleep.get("minutesAsleep")).doubleValue();
                    int efficiency = ((Number) mainSleep.get("efficiency")).intValue();
                    int minutesToFallAsleep = ((Number) mainSleep.get("minutesToFallAsleep")).intValue();

                    influxWriteService.writeData("sleep", Map.of("source", "fitbit"), "minutesAsleep", minutesAsleep, dayStart);
                    influxWriteService.writeData("sleep", Map.of("source", "fitbit"), "efficiency", efficiency, dayStart);
                    influxWriteService.writeData("sleep", Map.of("source", "fitbit"), "minutesToFallAsleep", minutesToFallAsleep, dayStart);
                }
            }

            var sleepSummary = (Map<String, Object>) data.get("summary");
            if (sleepSummary != null){
                var stages = (Map<String, Object>) sleepSummary.get("stages");
                if (stages != null){
                    int deep = ((Number) stages.get("deep")).intValue();
                    int light = ((Number) stages.get("light")).intValue();
                    int rem = ((Number) stages.get("rem")).intValue();
                    int wake = ((Number) stages.get("wake")).intValue();

                    influxWriteService.writeData("sleep_stages", Map.of("source", "fitbit"), "deep", deep, dayStart);
                    influxWriteService.writeData("sleep_stages", Map.of("source", "fitbit"), "light", light, dayStart);
                    influxWriteService.writeData("sleep_stages", Map.of("source", "fitbit"), "rem", rem, dayStart);
                    influxWriteService.writeData("sleep_stages", Map.of("source", "fitbit"), "wake", wake, dayStart);
                }
                double totalMinutesAsleep = ((Number) sleepSummary.get("totalMinutesAsleep")).doubleValue();
                int totalSleepRecords = ((Number) sleepSummary.get("totalSleepRecords")).intValue();

                influxWriteService.writeData("sleep_summary", Map.of("source", "fitbit"), "totalMinutesAsleep", totalMinutesAsleep, dayStart);
                influxWriteService.writeData("sleep_summary", Map.of("source", "fitbit"), "totalSleepRecords", totalSleepRecords, dayStart);
            }
        } catch (Exception e) {
            log.error("Sync failed for sleep on {}: {}", date, e.getMessage());
        }
    }
}
