package com.fbtracker.backend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private final OAuthTokenRepository oauthTokenRepository;
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final FitbitOAuth2UserService fitbitOAuth2UserService;
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth -> oauth
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
                    oauthToken.setRefreshToken(client.getRefreshToken().getTokenValue());
                    oauthToken.setExpiresAt(client.getAccessToken().getExpiresAt());

                    oauthTokenRepository.deleteAll();
                    oauthTokenRepository.save(oauthToken);
                    response.sendRedirect("/");
                })
        );

        return http.build();
    }

    public SecurityConfig(OAuthTokenRepository oauthTokenRepository, OAuth2AuthorizedClientService authorizedClientService, FitbitOAuth2UserService fitbitOAuth2UserService) {
        this.oauthTokenRepository = oauthTokenRepository;
        this.authorizedClientService = authorizedClientService;
        this.fitbitOAuth2UserService = fitbitOAuth2UserService;
    }
}
