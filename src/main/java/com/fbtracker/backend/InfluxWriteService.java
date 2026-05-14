package com.fbtracker.backend;

import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;

@Service
public class InfluxWriteService {
    private final InfluxDBClient influxDBClient;

    public InfluxWriteService(InfluxDBClient influxDBClient) {
        this.influxDBClient = influxDBClient;
    }

    public void writeData(String measurement, Map<String, String> tags, String fieldName, double fieldValue, Instant timestamp) {
        Point point = Point.measurement(measurement)
                .addTags(tags)
                .addField(fieldName, fieldValue)
                .time(timestamp.toEpochMilli(), WritePrecision.MS);
        influxDBClient.getWriteApiBlocking().writePoint(point);
    }
}
