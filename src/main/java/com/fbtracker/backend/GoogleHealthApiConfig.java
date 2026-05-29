package com.fbtracker.backend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;

/**
 * RestClient pointed at the Google Health API. Parallels {@link FitbitApiConfig}
 * but is only created under the {@code google} profile, so the default Fitbit
 * wiring is untouched.
 */
@Configuration
@Profile("google")
public class GoogleHealthApiConfig {

    @Bean
    public RestClient googleHealthRestClient(GoogleHealthProperties properties) {
        return RestClient.create(properties.getBaseUrl());
    }
}
