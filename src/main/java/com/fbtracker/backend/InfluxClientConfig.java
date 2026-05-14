package com.fbtracker.backend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;

@Configuration
public class InfluxClientConfig {
    private final InfluxProperties influxProperties;
    public InfluxClientConfig(InfluxProperties influxProperties) {
        this.influxProperties = influxProperties;
    }
    @Bean
    public InfluxDBClient influxDBClient() {
        return InfluxDBClientFactory.create(influxProperties.getUrl(), influxProperties.getToken().toCharArray(), influxProperties.getOrg(), influxProperties.getBucket());
    }
}
