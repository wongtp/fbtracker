package com.fbtracker.backend;

import java.time.Instant;
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
        var data = fitbitApiClient.fetchData("/1/user/-/activities/" + activity + "/date/today/1d.json");
        var results = (List<Map<String, Object>>) data.get("activities-" + activity);
        double count = Double.parseDouble((String) results.get(0).get("value"));
        influxWriteService.writeData(activity, Map.of("source", "fitbit"), "count", count, Instant.now());
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
        if (sleepData == null || sleepData.isEmpty()) return;
        Map<String, Object> mainSleep = sleepData.get(0);

        double minutesAsleep = ((Number) mainSleep.get("minutesAsleep")).doubleValue();
        influxWriteService.writeData("sleep", Map.of("source", "fitbit"), "minutesAsleep", minutesAsleep, Instant.now());
    }
}
