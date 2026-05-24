package com.fbtracker.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class FitbitApiClientTest {

    @Mock
    private RestClient fitbitRestClient;

    @Mock
    private OAuthTokenRepository tokenRepository;

    @Mock
    private TokenRefreshService refreshService;

    @InjectMocks
    private FitbitApiClient fitbitApiClient;

    @Mock
    private RestClient.RequestHeadersUriSpec<?> uriSpec;

    @Mock
    private RestClient.RequestHeadersSpec<?> headersSpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private OAuthToken validToken;

    @BeforeEach
    void setUp() {
        validToken = new OAuthToken();
        validToken.setAccessToken("valid-access-token");
        validToken.setRefreshToken("valid-refresh-token");
        validToken.setExpiresAt(Instant.now().plusSeconds(3600));
    }

    private void stubRestClientChain(Map<String, Object> bodyToReturn) {
        doReturn(uriSpec).when(fitbitRestClient).get();
        doReturn(headersSpec).when(uriSpec).uri(anyString());
        doReturn(headersSpec).when(headersSpec).header(anyString(), anyString());
        doReturn(responseSpec).when(headersSpec).retrieve();
        doReturn(bodyToReturn).when(responseSpec).body(Map.class);
    }

    @Test
    void fetchData_returnsResponse_whenTokenValidAndApiSucceeds() {
        // Arrange
        Map<String, Object> expectedResponse = Map.of("activities-steps", "fake data");
        when(tokenRepository.findTopByOrderByExpiresAtDesc())
            .thenReturn(Optional.of(validToken));
        stubRestClientChain(expectedResponse);

        // Act
        Map<String, Object> result = fitbitApiClient.fetchData("/1/user/-/activities/steps/date/today/1d/1min.json");

        // Assert
        assertThat(result).isEqualTo(expectedResponse);
        verify(refreshService, never()).refreshToken();
    }

    @Test
    void fetchData_refreshesProactively_whenTokenExpiredInDb() {
        // Arrange
        OAuthToken expiredToken = new OAuthToken();
        expiredToken.setAccessToken("expired-access-token");
        expiredToken.setRefreshToken("expired-refresh-token");
        expiredToken.setExpiresAt(Instant.now().minusSeconds(60));

        Map<String, Object> expectedResponse = Map.of("activities-steps", "fake data");

        when(tokenRepository.findTopByOrderByExpiresAtDesc())
            .thenReturn(Optional.of(expiredToken))   // first call: expired
            .thenReturn(Optional.of(validToken));    // after refresh: fresh

        stubRestClientChain(expectedResponse);

        // Act
        Map<String, Object> result = fitbitApiClient.fetchData("/some/endpoint");

        // Assert
        assertThat(result).isEqualTo(expectedResponse);
        verify(refreshService).refreshToken();   // refresh was called exactly once
    }

    @Test
    void fetchData_refreshesAndRetries_when401Received() {
        // Arrange
        Map<String, Object> expectedResponse = Map.of("activities-steps", "fake data");
        HttpClientErrorException unauthorized = HttpClientErrorException.create(
            HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null);

        when(tokenRepository.findTopByOrderByExpiresAtDesc())
            .thenReturn(Optional.of(validToken));

        // Stub the chain so the first body() call throws 401, the second returns the response
        doReturn(uriSpec).when(fitbitRestClient).get();
        doReturn(headersSpec).when(uriSpec).uri(anyString());
        doReturn(headersSpec).when(headersSpec).header(anyString(), anyString());
        doReturn(responseSpec).when(headersSpec).retrieve();
        doThrow(unauthorized).doReturn(expectedResponse).when(responseSpec).body(Map.class);

        // Act
        Map<String, Object> result = fitbitApiClient.fetchData("/some/endpoint");

        // Assert
        assertThat(result).isEqualTo(expectedResponse);
        verify(refreshService, times(1)).refreshToken();   // exactly one refresh
        verify(responseSpec, times(2)).body(Map.class);     // called twice (initial + retry)
    }

    @Test
    void fetchData_throwsAndDoesNotRecurse_whenSecond401Received() {
        // Regression test for the infinite-recursion bug.
        // After one refresh, a second 401 must propagate, not trigger another retry.
        // Arrange
        HttpClientErrorException unauthorized = HttpClientErrorException.create(
            HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null);

        when(tokenRepository.findTopByOrderByExpiresAtDesc())
            .thenReturn(Optional.of(validToken));

        doReturn(uriSpec).when(fitbitRestClient).get();
        doReturn(headersSpec).when(uriSpec).uri(anyString());
        doReturn(headersSpec).when(headersSpec).header(anyString(), anyString());
        doReturn(responseSpec).when(headersSpec).retrieve();
        doThrow(unauthorized).when(responseSpec).body(Map.class);   // throws every time

        // Act + Assert
        assertThatThrownBy(() -> fitbitApiClient.fetchData("/some/endpoint"))
            .isInstanceOf(HttpClientErrorException.Unauthorized.class);

        verify(refreshService, times(1)).refreshToken();   // refresh attempted exactly once
        verify(responseSpec, times(2)).body(Map.class);     // exactly two HTTP attempts
    }

    @Test
    void fetchData_propagates_whenNon401ErrorReceived() {
        // Arrange
        HttpServerErrorException serverError = HttpServerErrorException.create(
            HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", HttpHeaders.EMPTY, new byte[0], null);

        when(tokenRepository.findTopByOrderByExpiresAtDesc())
            .thenReturn(Optional.of(validToken));

        doReturn(uriSpec).when(fitbitRestClient).get();
        doReturn(headersSpec).when(uriSpec).uri(anyString());
        doReturn(headersSpec).when(headersSpec).header(anyString(), anyString());
        doReturn(responseSpec).when(headersSpec).retrieve();
        doThrow(serverError).when(responseSpec).body(Map.class);

        // Act + Assert
        assertThatThrownBy(() -> fitbitApiClient.fetchData("/some/endpoint"))
            .isInstanceOf(HttpServerErrorException.class);

        verify(refreshService, never()).refreshToken();    // 500 should NOT trigger refresh
        verify(responseSpec, times(1)).body(Map.class);     // exactly one HTTP attempt, no retry
    }
}
