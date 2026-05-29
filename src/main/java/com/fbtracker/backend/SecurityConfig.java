package com.fbtracker.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    private final OAuthTokenRepository oauthTokenRepository;
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final FitbitOAuth2UserService fitbitOAuth2UserService;
    private final ObjectProvider<OAuth2AuthorizationRequestResolver> authorizationRequestResolver;
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/fithub/**").permitAll()
                .requestMatchers("/fithub", "/fithub/**").permitAll()
                // dev-only Google Health probe (controller exists only under the 'google' profile)
                .requestMatchers("/dev/**").permitAll()
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth -> {
                oauth
                    .userInfoEndpoint(u -> u
                        .userService(fitbitOAuth2UserService)
                    )
                    .redirectionEndpoint(r -> r
                        .baseUri("/callback")
                    )
                    .successHandler((request, response, authentication) -> {
                        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
                        OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient(
                            token.getAuthorizedClientRegistrationId(),
                            token.getName()
                        );

                        OAuthToken oauthToken = new OAuthToken();
                        oauthToken.setAccessToken(client.getAccessToken().getTokenValue());
                        // Google may omit the refresh token (e.g. without offline access / consent);
                        // guard so login still succeeds, though refresh will then fail until re-consent.
                        if (client.getRefreshToken() != null) {
                            oauthToken.setRefreshToken(client.getRefreshToken().getTokenValue());
                        } else {
                            log.warn("No refresh token returned for {} — token refresh will require re-login",
                                token.getAuthorizedClientRegistrationId());
                        }
                        oauthToken.setExpiresAt(client.getAccessToken().getExpiresAt());

                        oauthTokenRepository.deleteAll();
                        oauthTokenRepository.save(oauthToken);
                        response.sendRedirect("/");
                    });
                // Under the 'google' profile, apply the offline-access resolver (refresh tokens).
                OAuth2AuthorizationRequestResolver resolver = authorizationRequestResolver.getIfAvailable();
                if (resolver != null) {
                    oauth.authorizationEndpoint(a -> a.authorizationRequestResolver(resolver));
                }
            });

        return http.build();
    }

    public SecurityConfig(OAuthTokenRepository oauthTokenRepository, OAuth2AuthorizedClientService authorizedClientService,
                          FitbitOAuth2UserService fitbitOAuth2UserService,
                          ObjectProvider<OAuth2AuthorizationRequestResolver> authorizationRequestResolver) {
        this.oauthTokenRepository = oauthTokenRepository;
        this.authorizedClientService = authorizedClientService;
        this.fitbitOAuth2UserService = fitbitOAuth2UserService;
        this.authorizationRequestResolver = authorizationRequestResolver;
    }
}
