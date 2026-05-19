package com.fbtracker.backend;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SyncService {
    private final FitbitApiClient fitbitApiClient;
    private final InfluxWriteService influxWriteService;

    public SyncService(FitbitApiClient fitbitApiClient, InfluxWriteService influxWriteService){
        this.fitbitApiClient = fitbitApiClient;
        this.influxWriteService = influxWriteService;
    }

    @Scheduled(cron = "0 0 * * * *")
    public void syncActivities() {
        activityHelper("steps");
        activityHelper("calories");
        activityHelper("distance");
    }

    private void activityHelper(String activity) {
        var data = fitbitApiClient.fetchData("/1/user/-/activities/" + activity + "/date/today/1d/1min.json");
        var resultsIntraday = (Map<String, Object>) data.get("activities-" + activity+"-intraday");
        if (resultsIntraday != null){
            var dataList = (List<Map<String, Object>>) resultsIntraday.get("dataset");
            if (dataList != null && !dataList.isEmpty()) {
                for (Map<String, Object> entry : dataList) {
                    double count = ((Number) entry.get("value")).doubleValue();
                    Instant timestamp = LocalDate.now().atTime(LocalTime.parse(entry.get("time").toString())).toInstant(ZoneOffset.UTC);
                    influxWriteService.writeData(activity+"_intraday", Map.of("source", "fitbit"), "count", count, timestamp);
                }
            }
        }
    }

    @Scheduled(cron = "0 0 * * * *")
    public void syncHeartrate() {
        var data = fitbitApiClient.fetchData("/1/user/-/activities/heart/date/today/1d/1min.json");
        var heartList = (List<Map<String, Object>>) data.get("activities-heart");
        if (heartList != null && !heartList.isEmpty()){
            var value = (Map<String, Object>) heartList.get(0).get("value");
            if (value != null && value.get("restingHeartRate") != null) {
                double resting = ((Number) value.get("restingHeartRate")).doubleValue();
                influxWriteService.writeData("heartrate", Map.of("source", "fitbit"), "resting", resting, Instant.now());
            }
        }

        var heartIntraday = (Map<String, Object>) data.get("activities-heart-intraday");
        if (heartIntraday != null){
            var heartData = (List<Map<String, Object>>) heartIntraday.get("dataset");
            if (heartData != null && !heartData.isEmpty()) {
                for (Map<String, Object> entry : heartData) {
                    double bpm = ((Number) entry.get("value")).doubleValue();
                    Instant timestamp = LocalDate.now().atTime(LocalTime.parse(entry.get("time").toString())).toInstant(ZoneOffset.UTC);
                    influxWriteService.writeData("heartrate_intraday", Map.of("source", "fitbit"), "bpm", bpm, timestamp);
                }
            }
        }
    }

    @Scheduled(cron = "0 0 * * * *")
    public void syncOxygen() {
        var data = fitbitApiClient.fetchData("/1/user/-/spo2/date/today.json");
        var oxygen = (Map<String, Object>) data.get("value");
        if (oxygen == null) return;
        double avg = ((Number) oxygen.get("avg")).doubleValue();
        influxWriteService.writeData("oxygen", Map.of("source", "fitbit"), "avg", avg, Instant.now());
    }

    @Scheduled(cron = "0 0 * * * *")
    public void syncSleep() {
        var data = fitbitApiClient.fetchData("/1/user/-/sleep/date/today.json");
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

                influxWriteService.writeData("sleep", Map.of("source", "fitbit"), "minutesAsleep", minutesAsleep, Instant.now());
                influxWriteService.writeData("sleep", Map.of("source", "fitbit"), "efficiency", efficiency, Instant.now());
                influxWriteService.writeData("sleep", Map.of("source", "fitbit"), "minutesToFallAsleep", minutesToFallAsleep, Instant.now());
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

                influxWriteService.writeData("sleep_stages", Map.of("source", "fitbit"), "deep", deep, Instant.now());
                influxWriteService.writeData("sleep_stages", Map.of("source", "fitbit"), "light", light, Instant.now());
                influxWriteService.writeData("sleep_stages", Map.of("source", "fitbit"), "rem", rem, Instant.now());
                influxWriteService.writeData("sleep_stages", Map.of("source", "fitbit"), "wake", wake, Instant.now());
            }
            double totalMinutesAsleep = ((Number) sleepSummary.get("totalMinutesAsleep")).doubleValue();
            int totalSleepRecords = ((Number) sleepSummary.get("totalSleepRecords")).intValue();

            influxWriteService.writeData("sleep_summary", Map.of("source", "fitbit"), "totalMinutesAsleep", totalMinutesAsleep, Instant.now());
            influxWriteService.writeData("sleep_summary", Map.of("source", "fitbit"), "totalSleepRecords", totalSleepRecords, Instant.now());
        }
    }

}
