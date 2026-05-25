package com.fbtracker.backend;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class AdminController {
    private final DiscordNotificationService discordService;
    private final DailySummaryService dailySummaryService;

    public AdminController(DiscordNotificationService discordService, DailySummaryService dailySummaryService) {
        this.discordService = discordService;
        this.dailySummaryService = dailySummaryService;
    }

    @GetMapping("/test-discord")
    public String testDiscord(@RequestParam(defaultValue = "test from fbtracker") String message) {
        discordService.sendMessage(message);
        return "Discord notification sent: " + message;
    }

    @GetMapping("/test-summary")
    public String testSummary() {
        dailySummaryService.sendDailySummary();
        return "Daily summary triggered — check Discord";
    }
}
