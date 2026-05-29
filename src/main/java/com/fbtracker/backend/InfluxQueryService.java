package com.fbtracker.backend;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;

@Service
public class InfluxQueryService {
    private static final Logger log = LoggerFactory.getLogger(InfluxQueryService.class);

    private final InfluxDBClient influxDBClient;
    private final InfluxProperties influxProperties;
    private final String timezone;

    public InfluxQueryService(InfluxDBClient influxDBClient, InfluxProperties influxProperties,
                              @Value("${app.timezone}") String timezone) {
        this.influxDBClient = influxDBClient;
        this.influxProperties = influxProperties;
        this.timezone = timezone;
    }

    public double getDailyTotal(String measurement) {
        String flux = String.format("""
            import "timezone"
            option location = timezone.location(name: "%s")

            from(bucket: "%s")
              |> range(start: today(), stop: now())
              |> filter(fn: (r) => r._measurement == "%s")
              |> sum()
            """, timezone, influxProperties.getBucket(), measurement);

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
            """, timezone, influxProperties.getBucket(), measurement, field);

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

    /**
     * Daily SUM per day for additive intraday measurements (steps/calories/distance)
     * over an inclusive date range. Returns one entry per day that has data.
     */
    public Map<LocalDate, Double> getDailySumRange(String measurement, String field,
                                                   LocalDate start, LocalDate end) {
        return runRangeQuery(rangeFlux(measurement, field, start, end, "sum"),
                measurement + "." + field);
    }

    /**
     * Per-day single value for daily measurements stored at local midnight
     * (sleep.*, heartrate.resting, oxygen.avg) over an inclusive date range.
     */
    public Map<LocalDate, Double> getDailyValueRange(String measurement, String field,
                                                     LocalDate start, LocalDate end) {
        return runRangeQuery(rangeFlux(measurement, field, start, end, "last"),
                measurement + "." + field);
    }

    private String rangeFlux(String measurement, String field, LocalDate start, LocalDate end, String fn) {
        ZoneId zone = ZoneId.of(timezone);
        // Local-midnight boundaries; stop is exclusive so the last day is included.
        Instant startInstant = start.atStartOfDay(zone).toInstant();
        Instant stopInstant = end.plusDays(1).atStartOfDay(zone).toInstant();
        return String.format("""
            import "timezone"
            option location = timezone.location(name: "%s")

            from(bucket: "%s")
              |> range(start: %s, stop: %s)
              |> filter(fn: (r) => r._measurement == "%s")
              |> filter(fn: (r) => r._field == "%s")
              |> aggregateWindow(every: 1d, fn: %s, timeSrc: "_start", createEmpty: true)
            """, timezone, influxProperties.getBucket(), startInstant, stopInstant,
            measurement, field, fn);
    }

    private Map<LocalDate, Double> runRangeQuery(String flux, String label) {
        Map<LocalDate, Double> out = new LinkedHashMap<>();
        ZoneId zone = ZoneId.of(timezone);
        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(flux);
            for (FluxTable table : tables) {
                for (FluxRecord rec : table.getRecords()) {
                    Object value = rec.getValue();   // null for gaps filled by createEmpty
                    Instant t = rec.getTime();       // window _start instant
                    if (value == null || t == null) {
                        continue;
                    }
                    LocalDate day = t.atZone(zone).toLocalDate();
                    out.put(day, ((Number) value).doubleValue());
                }
            }
        } catch (Exception e) {
            log.error("Influx range query failed for {}: {}", label, e.getMessage());
        }
        return out;
    }
}
