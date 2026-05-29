package com.fbtracker.backend;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the Fithub dashboard at the clean URL /fithub (and /fithub/) by
 * redirecting to the static index.html, since Spring Boot only auto-resolves
 * a directory welcome page for the application root.
 */
@Configuration
public class FithubWebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/fithub", "/fithub/index.html");
        registry.addRedirectViewController("/fithub/", "/fithub/index.html");
    }
}
