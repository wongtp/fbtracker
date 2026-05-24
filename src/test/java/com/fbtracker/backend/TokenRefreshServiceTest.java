package com.fbtracker.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class TokenRefreshServiceTest {

    @Mock
    private RestClient fitbitRestClient;

    @Mock
    private OAuthTokenRepository tokenRepository;

    @Mock
    private FitbitProperties fitbitProperties;

    @InjectMocks
    private TokenRefreshService tokenRefreshService;

    @Mock
    private RestClient.RequestBodyUriSpec bodyUriSpec;

    @Mock
    private RestClient.RequestBodySpec bodySpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private OAuthToken existingToken;

    @BeforeEach
    void setUp() {
        existingToken = new OAuthToken();
        existingToken.setAccessToken("old-access-token");
        existingToken.setRefreshToken("old-refresh-token");
        existingToken.setExpiresAt(Instant.now().minusSeconds(60));
    }

    private void stubRestClientChain(Map<String, Object> bodyToReturn) {
        doReturn(bodyUriSpec).when(fitbitRestClient).post();
        doReturn(bodySpec).when(bodyUriSpec).uri(anyString());
        doReturn(bodySpec).when(bodySpec).header(anyString(), anyString());
        doReturn(bodySpec).when(bodySpec).contentType(any(MediaType.class));
        doReturn(bodySpec).when(bodySpec).body(anyString());
        doReturn(responseSpec).when(bodySpec).retrieve();
        doReturn(bodyToReturn).when(responseSpec).body(Map.class);
    }

    @Test
    void refreshToken_updatesToken_whenRefreshSucceeds() {
        // Arrange
        Map<String, Object> apiResponse = Map.of(
            "access_token", "new-access-token",
            "refresh_token", "new-refresh-token",
            "expires_in", 28800
        );
        when(tokenRepository.findTopByOrderByExpiresAtDesc())
            .thenReturn(Optional.of(existingToken));
        when(fitbitProperties.getClientId()).thenReturn("test-client-id");
        when(fitbitProperties.getClientSecret()).thenReturn("test-client-secret");
        stubRestClientChain(apiResponse);

        Instant beforeCall = Instant.now();

        // Act
        tokenRefreshService.refreshToken();

        // Assert — capture what got saved and verify each field
        ArgumentCaptor<OAuthToken> tokenCaptor = ArgumentCaptor.forClass(OAuthToken.class);
        verify(tokenRepository).save(tokenCaptor.capture());

        OAuthToken saved = tokenCaptor.getValue();
        assertThat(saved.getAccessToken()).isEqualTo("new-access-token");
        assertThat(saved.getRefreshToken()).isEqualTo("new-refresh-token");

        // expiresAt should be approximately (beforeCall + 28800s), allow 5s tolerance for test runtime
        long actualExpirySeconds = saved.getExpiresAt().getEpochSecond() - beforeCall.getEpochSecond();
        assertThat(actualExpirySeconds).isBetween(28800L - 5, 28800L + 5);
    }

    @Test
    void refreshToken_throws_whenNoTokenInDb() {
        // Arrange
        when(tokenRepository.findTopByOrderByExpiresAtDesc())
            .thenReturn(Optional.empty());

        // Act + Assert
        assertThatThrownBy(() -> tokenRefreshService.refreshToken())
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("No valid OAuth token found");

        verify(tokenRepository, never()).save(any());
    }
}
