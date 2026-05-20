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
    private final RestClient fitbitRestClient;
    private final OAuthTokenRepository tokenRepository;
    private final TokenRefreshService refreshService;

    public FitbitApiClient(RestClient fitbitRestClient, OAuthTokenRepository tokenRepository, TokenRefreshService refreshService) {
        this.fitbitRestClient = fitbitRestClient;
        this.tokenRepository = tokenRepository;
        this.refreshService = refreshService;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchData(String endpoint){

        OAuthToken token = tokenRepository.findTopByOrderByExpiresAtDesc()
                .orElseThrow(() -> new RuntimeException("No OAuth token found"));

        if (token.getExpiresAt().isBefore(Instant.now())) {
            try {
                refreshService.refreshToken();
            } catch (Exception refreshEx) {
                log.error("Token refresh failed — likely refresh token expired. Re-login required at /oauth2/authorization/fitbit");
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
                log.error("Token refresh failed — likely refresh token expired. Re-login required at /oauth2/authorization/fitbit");
                throw refreshEx;
            }
            String newToken = tokenRepository.findTopByOrderByExpiresAtDesc()
                .orElseThrow(() -> new RuntimeException("No OAuth token after refresh"))
                .getAccessToken();
            return doFetch(endpoint, newToken, false);
        }

    }
}
