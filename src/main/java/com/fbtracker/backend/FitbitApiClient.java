package com.fbtracker.backend;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class FitbitApiClient {
    private final RestClient fitbitRestClient;
    private final OAuthTokenRepository tokenRepository;

    public FitbitApiClient(RestClient fitbitRestClient, OAuthTokenRepository tokenRepository) {
        this.fitbitRestClient = fitbitRestClient;
        this.tokenRepository = tokenRepository;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchData(String endpoint){
        String accessToken = tokenRepository.findAll().stream()
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No OAuth token found"))
                .getAccessToken();
        return fitbitRestClient.get()
                .uri(endpoint)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(Map.class);
    }
}
