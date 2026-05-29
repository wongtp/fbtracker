package com.fbtracker.backend;

import java.time.Instant;
import java.time.LocalDate;
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
    private static final Map<String, String> SOURCE = Map.of("source", "fitbit");

    private final HealthDataClient healthDataClient;
    private final InfluxWriteService influxWriteService;
    private final ZoneId zoneId;

    public SyncService(HealthDataClient healthDataClient, InfluxWriteService influxWriteService,
                       @Value("${app.timezone}") String timezone){
        this.healthDataClient = healthDataClient;
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

    /** Calories only — used by the calories-only backfill (daily totals from Google). */
    public void syncCaloriesForDate(LocalDate date) {
        activityHelper("calories", date);
    }

    private void activityHelper(String activity, LocalDate date) {
        try {
            List<IntradayPoint> points = healthDataClient.fetchActivityIntraday(activity, date);
            if (points.isEmpty()) {
                log.warn("No intraday data for {} on {}", activity, date);
                return;
            }
            double dayTotal = points.stream().mapToDouble(IntradayPoint::value).sum();
            if (dayTotal == 0) {
                log.info("Skipping {} on {}: all-zero day (likely pre-device-activation)", activity, date);
                return;
            }
            log.info("Writing {} intraday datapoints for {} on {}", points.size(), activity, date);
            for (IntradayPoint p : points) {
                influxWriteService.writeData(activity + "_intraday", SOURCE, "count", p.value(), p.timestamp());
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
            HeartRateData heart = healthDataClient.fetchHeartRate(date);
            Instant dayStart = date.atStartOfDay(zoneId).toInstant();

            if (heart.restingHeartRate() != null) {
                influxWriteService.writeData("heartrate", SOURCE, "resting", heart.restingHeartRate(), dayStart);
            }

            List<IntradayPoint> intraday = heart.intraday();
            if (intraday.isEmpty()) {
                log.warn("Empty intraday dataset for heartrate on {}", date);
                return;
            }
            double dayTotal = intraday.stream().mapToDouble(IntradayPoint::value).sum();
            if (dayTotal == 0) {
                log.info("Skipping heartrate on {}: all-zero day (likely pre-device-activation)", date);
                return;
            }
            log.info("Writing {} intraday datapoints for heartrate on {}", intraday.size(), date);
            for (IntradayPoint p : intraday) {
                influxWriteService.writeData("heartrate_intraday", SOURCE, "bpm", p.value(), p.timestamp());
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
            OxygenData oxygen = healthDataClient.fetchOxygen(date);
            if (oxygen.avg() == null) {
                return;
            }
            if (oxygen.avg() == 0) {
                log.info("Skipping oxygen on {}: zero value (likely pre-device-activation)", date);
                return;
            }
            Instant dayStart = date.atStartOfDay(zoneId).toInstant();
            influxWriteService.writeData("oxygen", SOURCE, "avg", oxygen.avg(), dayStart);
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
            SleepData sleep = healthDataClient.fetchSleep(date);
            Instant dayStart = date.atStartOfDay(zoneId).toInstant();

            SleepData.MainSleep mainSleep = sleep.mainSleep();
            if (mainSleep != null) {
                influxWriteService.writeData("sleep", SOURCE, "minutesAsleep", mainSleep.minutesAsleep(), dayStart);
                influxWriteService.writeData("sleep", SOURCE, "efficiency", mainSleep.efficiency(), dayStart);
                influxWriteService.writeData("sleep", SOURCE, "minutesToFallAsleep", mainSleep.minutesToFallAsleep(), dayStart);
            }

            SleepData.SleepSummary summary = sleep.summary();
            if (summary != null) {
                SleepData.SleepStages stages = summary.stages();
                if (stages != null) {
                    influxWriteService.writeData("sleep_stages", SOURCE, "deep", stages.deep(), dayStart);
                    influxWriteService.writeData("sleep_stages", SOURCE, "light", stages.light(), dayStart);
                    influxWriteService.writeData("sleep_stages", SOURCE, "rem", stages.rem(), dayStart);
                    influxWriteService.writeData("sleep_stages", SOURCE, "wake", stages.wake(), dayStart);
                }
                influxWriteService.writeData("sleep_summary", SOURCE, "totalMinutesAsleep", summary.totalMinutesAsleep(), dayStart);
                influxWriteService.writeData("sleep_summary", SOURCE, "totalSleepRecords", summary.totalSleepRecords(), dayStart);
            }
        } catch (Exception e) {
            log.error("Sync failed for sleep on {}: {}", date, e.getMessage());
        }
    }
}
