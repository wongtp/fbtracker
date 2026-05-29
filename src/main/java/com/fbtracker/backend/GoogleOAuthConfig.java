package com.fbtracker.backend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;

/**
 * Adds {@code access_type=offline} and {@code prompt=consent} to the Google authorization
 * request. Google only issues a refresh token when offline access is requested with a forced
 * consent prompt; without this the app gets an access-token-only grant and cannot refresh.
 *
 * <p>Profile-gated ({@code google}); {@link SecurityConfig} applies this resolver only when the
 * bean is present, so the default Fitbit flow is unchanged.
 */
@Configuration
@Profile("google")
public class GoogleOAuthConfig {

    @Bean
    public OAuth2AuthorizationRequestResolver googleAuthorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository) {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(
            clientRegistrationRepository,
            OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        resolver.setAuthorizationRequestCustomizer(customizer -> customizer.additionalParameters(params -> {
            params.put("access_type", "offline");
            params.put("prompt", "consent");
        }));
        return resolver;
    }
}
