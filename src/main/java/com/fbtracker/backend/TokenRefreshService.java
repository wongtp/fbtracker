package com.fbtracker.backend;

import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class TokenRefreshService {
    private final RestClient fitbitRestClient;
    private final FitbitProperties fitbitProperties;
    private final OAuthTokenRepository tokenRepository;

    public TokenRefreshService(OAuthTokenRepository tokenRepository, FitbitProperties fitbitProperties, RestClient fitbitRestClient) {
        this.fitbitProperties = fitbitProperties;
        this.tokenRepository = tokenRepository;
        this.fitbitRestClient = fitbitRestClient;
    }

    public void refreshToken() {
        OAuthToken token = tokenRepository.findAll().stream().max(Comparator.comparing(OAuthToken::getExpiresAt)).orElseThrow(() -> new RuntimeException("No valid OAuth token found"));
        
        String credentials = Base64.getEncoder().encodeToString(
            (fitbitProperties.getClientId() + ":" + fitbitProperties.getClientSecret()).getBytes()
        );

        Map<String, Object> response = fitbitRestClient.post()
            .uri("https://api.fitbit.com/oauth2/token")
            .header("Authorization", "Basic " + credentials)
            .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
            .body("grant_type=refresh_token&refresh_token=" + token.getRefreshToken())
            .retrieve()
            .body(Map.class);

        token.setAccessToken((String) response.get("access_token"));
        token.setRefreshToken((String) response.get("refresh_token"));
        token.setExpiresAt(Instant.now().plusSeconds(((Number) response.get("expires_in")).longValue()));
        tokenRepository.save(token);
    }

}
