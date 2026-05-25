package com.fbtracker.backend;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.query.FluxTable;

@Service
public class InfluxQueryService {
    private static final Logger log = LoggerFactory.getLogger(InfluxQueryService.class);
    private static final String TIMEZONE = "America/New_York";

    private final InfluxDBClient influxDBClient;
    private final InfluxProperties influxProperties;

    public InfluxQueryService(InfluxDBClient influxDBClient, InfluxProperties influxProperties) {
        this.influxDBClient = influxDBClient;
        this.influxProperties = influxProperties;
    }

    public double getDailyTotal(String measurement) {
        String flux = String.format("""
            import "timezone"
            option location = timezone.location(name: "%s")

            from(bucket: "%s")
              |> range(start: today(), stop: now())
              |> filter(fn: (r) => r._measurement == "%s")
              |> sum()
            """, TIMEZONE, influxProperties.getBucket(), measurement);

        return runScalarQuery(flux, measurement);
    }

    public double getLatestValue(String measurement, String field) {
        String flux = String.format("""
            import "timezone"
            option location = timezone.location(name: "%s")

            from(bucket: "%s")
              |> range(start: today(), stop: now())
              |> filter(fn: (r) => r._measurement == "%s")
              |> filter(fn: (r) => r._field == "%s")
              |> last()
            """, TIMEZONE, influxProperties.getBucket(), measurement, field);

        return runScalarQuery(flux, measurement + "." + field);
    }

    private double runScalarQuery(String flux, String label) {
        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(flux);
            if (tables.isEmpty() || tables.get(0).getRecords().isEmpty()) {
                return 0;
            }
            Object value = tables.get(0).getRecords().get(0).getValue();
            return value == null ? 0 : ((Number) value).doubleValue();
        } catch (Exception e) {
            log.error("Influx query failed for {}: {}", label, e.getMessage());
            return 0;
        }
    }
}
