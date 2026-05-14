package com.fbtracker.backend;

import java.time.Instant;
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
    public void syncData() {
        var data = fitbitApiClient.fetchData("/1/user/-/activities/steps/date/today/1d.json");
        // Process data and write to InfluxDB
        var steps = (java.util.List<Map<String, Object>>) data.get("activities-steps");
        double count = Double.parseDouble((String) steps.get(0).get("value"));
        influxWriteService.writeData("steps", Map.of("source", "fitbit"), "count", count, Instant.now());
    }
}
