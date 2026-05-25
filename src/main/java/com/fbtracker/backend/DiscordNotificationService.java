package com.fbtracker.backend;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class DiscordNotificationService {
    private static final Logger log = LoggerFactory.getLogger(DiscordNotificationService.class);

    private final RestClient restClient;
    private final DiscordProperties discordProperties;

    public DiscordNotificationService(RestClient.Builder restClientBuilder, DiscordProperties discordProperties) {
        this.restClient = restClientBuilder.build();
        this.discordProperties = discordProperties;
    }

    public void sendMessage(String content) {
        String url = discordProperties.getWebhookUrl();
        if (url == null || url.isBlank()) {
            log.warn("Discord webhook URL not configured; skipping notification");
            return;
        }
        try {
            restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("content", content))
                .retrieve()
                .toBodilessEntity();
        } catch (Exception e) {
            log.error("Failed to send Discord notification: {}", e.getMessage());
        }
    }
}
