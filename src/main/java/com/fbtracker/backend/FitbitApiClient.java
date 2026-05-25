package com.fbtracker.backend;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Service
public class FitbitApiClient {
    private static final Logger log = LoggerFactory.getLogger(FitbitApiClient.class);
    private static final String REFRESH_FAILURE_MESSAGE =
        "Fitbit token refresh failed. Re-login required at http://localhost:8080/oauth2/authorization/fitbit";

    private final RestClient fitbitRestClient;
    private final OAuthTokenRepository tokenRepository;
    private final TokenRefreshService refreshService;
    private final DiscordNotificationService discordService;

    public FitbitApiClient(RestClient fitbitRestClient, OAuthTokenRepository tokenRepository,
                           TokenRefreshService refreshService, DiscordNotificationService discordService) {
        this.fitbitRestClient = fitbitRestClient;
        this.tokenRepository = tokenRepository;
        this.refreshService = refreshService;
        this.discordService = discordService;
    }

    private void notifyRefreshFailure() {
        log.error("Token refresh failed — likely refresh token expired. Re-login required at /oauth2/authorization/fitbit");
        discordService.sendMessage(REFRESH_FAILURE_MESSAGE);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchData(String endpoint){

        OAuthToken token = tokenRepository.findTopByOrderByExpiresAtDesc()
                .orElseThrow(() -> new RuntimeException("No OAuth token found"));

        if (token.getExpiresAt().isBefore(Instant.now())) {
            try {
                refreshService.refreshToken();
            } catch (Exception refreshEx) {
                notifyRefreshFailure();
                throw refreshEx;
            }
            token = tokenRepository.findTopByOrderByExpiresAtDesc()
                .orElseThrow(() -> new RuntimeException("No OAuth token found after refresh"));
        }
        
        return doFetch(endpoint, token.getAccessToken(), true);
    }

    private Map<String, Object> doFetch(String endpoint, String accessToken, boolean canRetry) {
        try {
            return fitbitRestClient.get()
                .uri(endpoint)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(Map.class);
        } catch (HttpClientErrorException.Unauthorized e) {
            if (!canRetry) throw e;
            try {
                refreshService.refreshToken();
            } catch (Exception refreshEx) {
                notifyRefreshFailure();
                throw refreshEx;
            }
            String newToken = tokenRepository.findTopByOrderByExpiresAtDesc()
                .orElseThrow(() -> new RuntimeException("No OAuth token after refresh"))
                .getAccessToken();
            return doFetch(endpoint, newToken, false);
        }

    }
}
