package com.fbtracker.backend;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEV-ONLY probe for Google Health API response shapes. Profile-gated ({@code google}),
 * so it does not exist in the default Fitbit app.
 *
 * <p>Until the Google OAuth login flow is wired (P5), obtain an access token manually
 * from the <a href="https://developers.google.com/oauthplayground/">OAuth 2.0 Playground</a>
 * (configured with this app's client id/secret + the googlehealth scopes), then:
 *
 * <pre>
 *   curl -H "Authorization: Bearer ya29...." \
 *        "http://localhost:8080/dev/google-health/steps"
 * </pre>
 *
 * Returns the full unfiltered JSON envelope (recent data points) so we can confirm exact
 * field names and shapes.
 */
@RestController
@Profile("google")
@RequestMapping("/dev/google-health")
public class GoogleHealthDevController {

    private final GoogleHealthApiClient client;

    public GoogleHealthDevController(GoogleHealthApiClient client) {
        this.client = client;
    }

    /**
     * @param dataType kebab-case identifier, e.g. {@code steps}, {@code heart-rate},
     *                 {@code oxygen-saturation}, {@code sleep}
     */
    @GetMapping("/{dataType}")
    public Map<String, Object> probe(@PathVariable String dataType,
                                     @RequestHeader("Authorization") String authorization) {
        String token = authorization.replaceFirst("(?i)^Bearer ", "").trim();
        return client.rawListPage(dataType, token);
    }
}
