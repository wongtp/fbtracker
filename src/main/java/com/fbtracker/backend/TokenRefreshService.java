package com.fbtracker.backend;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class TokenRefreshService {
    private static final Logger log = LoggerFactory.getLogger(TokenRefreshService.class);
    private final RestClient fitbitRestClient;
    private final FitbitProperties fitbitProperties;
    private final OAuthTokenRepository tokenRepository;

    public TokenRefreshService(OAuthTokenRepository tokenRepository, FitbitProperties fitbitProperties, RestClient fitbitRestClient) {
        this.fitbitProperties = fitbitProperties;
        this.tokenRepository = tokenRepository;
        this.fitbitRestClient = fitbitRestClient;
    }

    public synchronized void refreshToken() {
        OAuthToken token = tokenRepository.findTopByOrderByExpiresAtDesc()
            .orElseThrow(() -> new RuntimeException("No valid OAuth token found"));

        // another caller may have already refreshed while we waited on the lock
        if (token.getExpiresAt().isAfter(Instant.now().plusSeconds(60))) {
            log.debug("Token already fresh (expires at {}), skipping refresh", token.getExpiresAt());
            return;
        }

        log.info("Refreshing Fitbit access token");
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
        log.info("Token refresh succeeded, new expiry: {}", token.getExpiresAt());
    }

}
