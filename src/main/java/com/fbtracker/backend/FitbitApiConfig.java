package com.fbtracker.backend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class FitbitApiConfig {
    @Bean
    public RestClient fitbitRestClient() {
        return RestClient.create("https://api.fitbit.com");
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
