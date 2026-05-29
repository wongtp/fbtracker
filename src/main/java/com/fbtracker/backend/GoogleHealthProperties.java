package com.fbtracker.backend;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Config for the Google Health API client. Only bound when the {@code google}
 * profile is active (see application-google.yml). Mirrors {@link FitbitProperties}.
 */
@Component
@Profile("google")
@ConfigurationProperties(prefix = "google-health")
public class GoogleHealthProperties {
    private String clientId;
    private String clientSecret;
    private String baseUrl = "https://health.googleapis.com/v4";

    public String getClientId() {
        return clientId;
    }
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }
    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getBaseUrl() {
        return baseUrl;
    }
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
