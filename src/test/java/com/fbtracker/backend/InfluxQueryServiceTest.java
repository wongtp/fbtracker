package com.fbtracker.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;

@ExtendWith(MockitoExtension.class)
class InfluxQueryServiceTest {

    private static final String TZ = "America/New_York";

    @Mock
    private InfluxDBClient influxDBClient;
    @Mock
    private QueryApi queryApi;

    private InfluxQueryService service;

    @BeforeEach
    void setUp() {
        InfluxProperties props = new InfluxProperties();
        props.setBucket("health");
        service = new InfluxQueryService(influxDBClient, props, TZ);
    }

    /** Build a FluxRecord whose _time is the local-midnight start of the given day. */
    private FluxRecord record(LocalDate day, Object value) {
        Instant start = day.atStartOfDay(ZoneId.of(TZ)).toInstant();
        FluxRecord rec = new FluxRecord(0);
        rec.getValues().put("_time", start);
        rec.getValues().put("_value", value);
        return rec;
    }

    private void stubQuery(FluxRecord... records) {
        FluxTable table = new FluxTable();
        table.getRecords().addAll(List.of(records));
        when(influxDBClient.getQueryApi()).thenReturn(queryApi);
        when(queryApi.query(anyString())).thenReturn(List.of(table));
    }

    @Test
    void getDailySumRange_mapsRecordsToLocalCalendarDays() {
        stubQuery(
            record(LocalDate.of(2025, 1, 1), 1000.0),
            record(LocalDate.of(2025, 1, 2), 2000.0));

        Map<LocalDate, Double> result = service.getDailySumRange(
            "calories_intraday", "count", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2));

        assertThat(result)
            .containsEntry(LocalDate.of(2025, 1, 1), 1000.0)
            .containsEntry(LocalDate.of(2025, 1, 2), 2000.0)
            .hasSize(2);
    }

    @Test
    void getDailyValueRange_skipsNullGapsFromCreateEmpty() {
        stubQuery(
            record(LocalDate.of(2025, 1, 1), 420.0),
            record(LocalDate.of(2025, 1, 2), null),   // createEmpty gap
            record(LocalDate.of(2025, 1, 3), 455.0));

        Map<LocalDate, Double> result = service.getDailyValueRange(
            "sleep", "minutesAsleep", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 3));

        assertThat(result)
            .containsEntry(LocalDate.of(2025, 1, 1), 420.0)
            .containsEntry(LocalDate.of(2025, 1, 3), 455.0)
            .doesNotContainKey(LocalDate.of(2025, 1, 2))
            .hasSize(2);
    }

    @Test
    void rangeQuery_returnsEmptyMap_onQueryFailure() {
        when(influxDBClient.getQueryApi()).thenReturn(queryApi);
        when(queryApi.query(anyString())).thenThrow(new RuntimeException("influx down"));

        Map<LocalDate, Double> result = service.getDailySumRange(
            "steps_intraday", "count", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2));

        assertThat(result).isEmpty();
    }
}
