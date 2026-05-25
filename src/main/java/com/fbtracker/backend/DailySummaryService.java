package com.fbtracker.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DailySummaryService {
    private static final Logger log = LoggerFactory.getLogger(DailySummaryService.class);

    private final InfluxQueryService queryService;
    private final DiscordNotificationService discordService;

    public DailySummaryService(InfluxQueryService queryService, DiscordNotificationService discordService) {
        this.queryService = queryService;
        this.discordService = discordService;
    }

    @Scheduled(cron = "0 0 22 * * *", zone = "America/New_York")
    public void sendDailySummary() {
        try {
            double steps = queryService.getDailyTotal("steps_intraday");
            double calories = queryService.getDailyTotal("calories_intraday");
            double distance = queryService.getDailyTotal("distance_intraday");
            double sleepMinutes = queryService.getLatestValue("sleep", "minutesAsleep");
            double sleepEfficiency = queryService.getLatestValue("sleep", "efficiency");

            int sleepHours = (int) (sleepMinutes / 60);
            int sleepMins = (int) (sleepMinutes % 60);

            String message = String.format("""
                **Daily Summary**
                Steps: %,.0f
                Calories: %,.0f
                Distance: %.2f mi
                Sleep: %dh %dm (%.0f%% efficient)""",
                steps, calories, distance, sleepHours, sleepMins, sleepEfficiency);

            discordService.sendMessage(message);
            log.info("Sent daily summary to Discord");
        } catch (Exception e) {
            log.error("Failed to send daily summary: {}", e.getMessage());
        }
    }
}
