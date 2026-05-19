package com.fbtracker.backend;

import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Service
public class FitbitApiClient {
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

        OAuthToken token = tokenRepository.findAll().stream()
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No OAuth token found"));

        if (token.getExpiresAt().isBefore(Instant.now())) {
            refreshService.refreshToken();
            token = tokenRepository.findAll().stream()
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No OAuth token found after refresh"));
        }

        String accessToken = token.getAccessToken();

        try {
            return fitbitRestClient.get()
                .uri(endpoint)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(Map.class);
        } catch (Exception e) {
            try {
                refreshService.refreshToken();
                return fetchData(endpoint);
            } catch (Exception refreshEx) {
                throw new RuntimeException("Token refresh failed: " + refreshEx.getMessage());
            }
        }

    }
}
