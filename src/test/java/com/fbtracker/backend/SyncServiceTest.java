package com.fbtracker.backend;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SyncServiceTest {

    @Mock
    private FitbitApiClient fitbitApiClient;

    @Mock
    private InfluxWriteService influxWriteService;

    @InjectMocks
    private SyncService syncService;

    private Map<String, Object> intradayResponse(String metric) {
        return Map.of(
            "activities-" + metric + "-intraday", Map.of(
                "dataset", List.of(
                    Map.of("time", "12:00:00", "value", 100),
                    Map.of("time", "12:01:00", "value", 200)
                )
            )
        );
    }

    @Test
    void syncActivities_continuesOtherMetrics_whenOneFails() {
        // Arrange — steps throws, calories + distance return valid data
        when(fitbitApiClient.fetchData(contains("steps")))
            .thenThrow(new RuntimeException("simulated steps failure"));
        when(fitbitApiClient.fetchData(contains("calories")))
            .thenReturn(intradayResponse("calories"));
        when(fitbitApiClient.fetchData(contains("distance")))
            .thenReturn(intradayResponse("distance"));

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
