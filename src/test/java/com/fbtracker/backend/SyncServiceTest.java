package com.fbtracker.backend;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SyncServiceTest {

    @Mock
    private HealthDataClient healthDataClient;

    @Mock
    private InfluxWriteService influxWriteService;

    private SyncService syncService;

    @BeforeEach
    void setUp() {
        syncService = new SyncService(healthDataClient, influxWriteService, "America/New_York");
    }

    private List<IntradayPoint> intradayPoints() {
        return List.of(
            new IntradayPoint(Instant.parse("2026-05-29T16:00:00Z"), 100),
            new IntradayPoint(Instant.parse("2026-05-29T16:01:00Z"), 200)
        );
    }

    @Test
    void syncActivities_continuesOtherMetrics_whenOneFails() {
        // Arrange — steps throws, calories + distance return valid data
        when(healthDataClient.fetchActivityIntraday(eq("steps"), any(LocalDate.class)))
            .thenThrow(new RuntimeException("simulated steps failure"));
        when(healthDataClient.fetchActivityIntraday(eq("calories"), any(LocalDate.class)))
            .thenReturn(intradayPoints());
        when(healthDataClient.fetchActivityIntraday(eq("distance"), any(LocalDate.class)))
            .thenReturn(intradayPoints());

        // Act
        syncService.syncActivities();

        // Assert — steps writes never happened, but calories + distance did
        verify(influxWriteService, never())
            .writeData(eq("steps_intraday"), any(), anyString(), anyDouble(), any(Instant.class));
        verify(influxWriteService, atLeastOnce())
            .writeData(eq("calories_intraday"), any(), anyString(), anyDouble(), any(Instant.class));
        verify(influxWriteService, atLeastOnce())
            .writeData(eq("distance_intraday"), any(), anyString(), anyDouble(), any(Instant.class));
    }
}
