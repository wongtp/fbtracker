package com.fbtracker.backend;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class DiscordNotificationServiceTest {

    @Mock
    private RestClient.Builder restClientBuilder;

    @Mock
    private RestClient restClient;

    @Mock
    private DiscordProperties discordProperties;

    @Mock
    private RestClient.RequestBodyUriSpec bodyUriSpec;

    @Mock
    private RestClient.RequestBodySpec bodySpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private DiscordNotificationService service;

    @BeforeEach
    void setUp() {
        when(restClientBuilder.build()).thenReturn(restClient);
        service = new DiscordNotificationService(restClientBuilder, discordProperties);
    }

    private void stubRestClientChain() {
        doReturn(bodyUriSpec).when(restClient).post();
        doReturn(bodySpec).when(bodyUriSpec).uri(anyString());
        doReturn(bodySpec).when(bodySpec).contentType(any(MediaType.class));
        doReturn(bodySpec).when(bodySpec).body(any(Map.class));
        doReturn(responseSpec).when(bodySpec).retrieve();
    }

    @Test
    void sendMessage_postsToWebhook_whenUrlConfigured() {
        // Arrange
        when(discordProperties.getWebhookUrl()).thenReturn("https://discord.com/api/webhooks/123/abc");
        stubRestClientChain();

        // Act
        service.sendMessage("hello world");

        // Assert
        verify(restClient).post();
        verify(bodyUriSpec).uri("https://discord.com/api/webhooks/123/abc");
        verify(bodySpec).contentType(MediaType.APPLICATION_JSON);
        verify(bodySpec).body(eq(Map.of("content", "hello world")));
        verify(responseSpec).toBodilessEntity();
    }

    @Test
    void sendMessage_skipsSending_whenUrlIsNull() {
        // Arrange
        when(discordProperties.getWebhookUrl()).thenReturn(null);

        // Act
        service.sendMessage("hello");

        // Assert
        verifyNoInteractions(restClient);
    }

    @Test
    void sendMessage_skipsSending_whenUrlIsBlank() {
        // Arrange
        when(discordProperties.getWebhookUrl()).thenReturn("   ");

        // Act
        service.sendMessage("hello");

        // Assert
        verifyNoInteractions(restClient);
    }

    @Test
    void sendMessage_swallowsException_whenHttpCallFails() {
        // Arrange
        when(discordProperties.getWebhookUrl()).thenReturn("https://discord.com/api/webhooks/123/abc");
        doReturn(bodyUriSpec).when(restClient).post();
        doReturn(bodySpec).when(bodyUriSpec).uri(anyString());
        doReturn(bodySpec).when(bodySpec).contentType(any(MediaType.class));
        doReturn(bodySpec).when(bodySpec).body(any(Map.class));
        doThrow(new RuntimeException("network failure")).when(bodySpec).retrieve();

        // Act + Assert — should NOT throw
        service.sendMessage("hello");

        // Verify retrieve was attempted exactly once (no retry on errors)
        verify(bodySpec).retrieve();
        verify(responseSpec, never()).toBodilessEntity();
    }
}
