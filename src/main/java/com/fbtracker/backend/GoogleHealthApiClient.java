package com.fbtracker.backend;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Client for the Google Health API (https://health.googleapis.com/v4), the
 * successor to the Fitbit Web API. Mirrors {@link FitbitApiClient}: handles
 * token freshness/refresh and surfaces raw JSON maps to callers.
 *
 * <p>Profile-gated ({@code google}) so it never loads in the default Fitbit app.
 * This is the P2 scaffold — request plumbing and auth are concrete; per-data-type
 * response parsing into Influx writes is wired in P3 once field names are
 * confirmed against the GA API (see open questions in MIGRATION.md).
 *
 * <p>Key differences from the legacy client this replaces:
 * <ul>
 *   <li>Date selection is a {@code filter} query param (civil time), not a URL path.</li>
 *   <li>Responses paginate via {@code nextPageToken}.</li>
 *   <li>Token refresh hits Google's token endpoint, not Fitbit's.</li>
 * </ul>
 */
@Service
@Profile("google")
public class GoogleHealthApiClient {
    private static final Logger log = LoggerFactory.getLogger(GoogleHealthApiClient.class);
    private static final String GOOGLE_TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String REFRESH_FAILURE_MESSAGE =
        "Google Health token refresh failed. Re-login required at http://localhost:8080/oauth2/authorization/google-health";

    private final RestClient googleHealthRestClient;
    private final OAuthTokenRepository tokenRepository;
    private final GoogleHealthProperties properties;
    private final DiscordNotificationService discordService;

    public GoogleHealthApiClient(RestClient googleHealthRestClient, OAuthTokenRepository tokenRepository,
                                 GoogleHealthProperties properties, DiscordNotificationService discordService) {
        this.googleHealthRestClient = googleHealthRestClient;
        this.tokenRepository = tokenRepository;
        this.properties = properties;
        this.discordService = discordService;
    }

    /**
     * List data points for a single day, following pagination. Used for intraday
     * data types (steps, distance, heart-rate, oxygen-saturation, sleep).
     *
     * @param dataType kebab-case identifier, e.g. "steps", "heart-rate"
     * @return all dataPoints across pages for the day
     */
    /**
     * Time field used to filter (and, for intraday types, parse) a list query, per record type.
     * Filter field names and value formats are per the Google Health API docs:
     * interval types filter on civil (local) start time; sample types on physical (UTC) time.
     */
    public enum TimeAxis {
        /** interval types (steps, distance): bounded by local civil start time. */
        INTERVAL("interval", "startTime", "interval.civil_start_time", true),
        /** sample types (heart-rate, oxygen-saturation): bounded by UTC physical time. */
        SAMPLE("sampleTime", "physicalTime", "sample_time.physical_time", false),
        /** sleep sessions: attributed by wake (civil end) time to match legacy /sleep/date. */
        SLEEP("interval", "endTime", "interval.civil_end_time", true);

        public final String timeObjKey;
        public final String timeField;
        final String filterField;
        final boolean civil;

        TimeAxis(String timeObjKey, String timeField, String filterField, boolean civil) {
            this.timeObjKey = timeObjKey;
            this.timeField = timeField;
            this.filterField = filterField;
            this.civil = civil;
        }
    }

    public List<Map<String, Object>> listDataPoints(String dataType, LocalDate date, ZoneId zone, TimeAxis axis) {
        return listDataPoints(dataType, date, zone, axis, null);
    }

    /**
     * @param accessTokenOverride when non-null, use this token directly and skip the
     *        managed token/refresh path. Intended for dev probing with a manually
     *        obtained token (e.g. OAuth Playground); see {@code GoogleHealthDevController}.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listDataPoints(String dataType, LocalDate date, ZoneId zone,
                                                     TimeAxis axis, String accessTokenOverride) {
        List<Map<String, Object>> all = new ArrayList<>();
        String filter = buildFilter(dataType, date, zone, axis);
        String pageToken = null;
        do {
            String token = accessTokenOverride != null ? accessTokenOverride : validAccessToken();
            Map<String, Object> page = doGet(dataType, filter, pageToken, token, accessTokenOverride == null);
            List<Map<String, Object>> points = (List<Map<String, Object>>) page.get("dataPoints");
            if (points != null) {
                all.addAll(points);
            }
            pageToken = (String) page.get("nextPageToken");
        } while (pageToken != null && !pageToken.isBlank());
        log.info("Fetched {} {} dataPoints for {}", all.size(), dataType, date);
        return all;
    }

    /**
     * Single raw list page (full envelope), unfiltered, using the supplied token without refresh.
     * For dev shape-discovery only — see {@code GoogleHealthDevController}.
     */
    public Map<String, Object> rawListPage(String dataType, String accessToken) {
        return doGet(dataType, null, null, accessToken, false);
    }

    /**
     * List the most recent data points for a type WITHOUT a date filter (first page only).
     * Used for daily-aggregate types (e.g. daily-resting-heart-rate) where the target date is
     * matched client-side. Date-bounded fetching of older dates needs the verified date filter
     * (see MIGRATION.md).
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listRecent(String dataType) {
        Map<String, Object> page = doGet(dataType, null, null, validAccessToken(), true);
        List<Map<String, Object>> points = (List<Map<String, Object>>) page.get("dataPoints");
        return points != null ? points : List.of();
    }

    /**
     * Build the date filter expression bounding the request to {@code date}, per record type.
     * Interval types filter on local civil start time; sample types on UTC physical time.
     * Returned raw/unencoded — the URI builder encodes the value exactly once (filter field is
     * snake_case; conjunction must be uppercase AND).
     */
    private String buildFilter(String dataType, LocalDate date, ZoneId zone, TimeAxis axis) {
        String field = dataType.replace('-', '_') + "." + axis.filterField;
        String start;
        String end;
        if (axis.civil) {
            start = date + "T00:00:00";
            end = date.plusDays(1) + "T00:00:00";
        } else {
            start = date.atStartOfDay(zone).toInstant().toString();
            end = date.plusDays(1).atStartOfDay(zone).toInstant().toString();
        }
        return field + " >= \"" + start + "\" AND " + field + " < \"" + end + "\"";
    }

    /**
     * GET dataPoints for a type, optionally filtered/paged. The filter and pageToken are passed
     * as raw query-param values so the URI builder encodes them once (passing a pre-encoded URI
     * string to RestClient would double-encode and break the filter operators).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> doGet(String dataType, String filter, String pageToken,
                                      String accessToken, boolean canRetry) {
        try {
            return googleHealthRestClient.get()
                .uri(builder -> {
                    builder.path("/users/me/dataTypes/{dataType}/dataPoints");
                    if (filter != null) {
                        builder.queryParam("filter", filter);
                    }
                    if (pageToken != null && !pageToken.isBlank()) {
                        builder.queryParam("pageToken", pageToken);
                    }
                    return builder.build(dataType);
                })
                .header("Authorization", "Bearer " + accessToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(Map.class);
        } catch (HttpClientErrorException.Unauthorized e) {
            if (!canRetry) throw e;
            refreshTokenOrNotify();
            return doGet(dataType, filter, pageToken, validAccessToken(), false);
        }
    }

    private String validAccessToken() {
        OAuthToken token = tokenRepository.findTopByOrderByExpiresAtDesc()
            .orElseThrow(() -> new RuntimeException("No OAuth token found"));
        if (token.getExpiresAt().isBefore(Instant.now())) {
            refreshTokenOrNotify();
            token = tokenRepository.findTopByOrderByExpiresAtDesc()
                .orElseThrow(() -> new RuntimeException("No OAuth token found after refresh"));
        }
        return token.getAccessToken();
    }

    private void refreshTokenOrNotify() {
        try {
            refreshToken();
        } catch (Exception refreshEx) {
            log.error("Google Health token refresh failed — re-login required", refreshEx);
            discordService.sendMessage(REFRESH_FAILURE_MESSAGE);
            throw refreshEx;
        }
    }

    /**
     * Refresh the access token against Google's token endpoint. Unlike the Fitbit
     * flow, Google does not always return a new refresh_token, so we keep the
     * existing one when absent.
     */
    @SuppressWarnings("unchecked")
    synchronized void refreshToken() {
        OAuthToken token = tokenRepository.findTopByOrderByExpiresAtDesc()
            .orElseThrow(() -> new RuntimeException("No valid OAuth token found"));
        if (token.getExpiresAt().isAfter(Instant.now().plusSeconds(60))) {
            log.debug("Token already fresh (expires at {}), skipping refresh", token.getExpiresAt());
            return;
        }

        log.info("Refreshing Google Health access token");
        String credentials = Base64.getEncoder().encodeToString(
            (properties.getClientId() + ":" + properties.getClientSecret()).getBytes());

        Map<String, Object> response = googleHealthRestClient.post()
            .uri(GOOGLE_TOKEN_URI)
            .header("Authorization", "Basic " + credentials)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("grant_type=refresh_token&refresh_token=" + token.getRefreshToken())
            .retrieve()
            .body(Map.class);

        token.setAccessToken((String) response.get("access_token"));
        String newRefresh = (String) response.get("refresh_token");
        if (newRefresh != null) {
            token.setRefreshToken(newRefresh);
        }
        token.setExpiresAt(Instant.now().plusSeconds(((Number) response.get("expires_in")).longValue()));
        tokenRepository.save(token);
        log.info("Token refresh succeeded, new expiry: {}", token.getExpiresAt());
    }

    // TODO(P3): dailyRollUp(dataType, date) for non-intraday types:
    //   total-calories, daily-resting-heart-rate.
    //   POST /users/me/dataTypes/{type}/dataPoints:dailyRollUp with a civil-time
    //   range body { "range": { "start": {date,time}, "end": {date,time} }, "windowSizeDays": 1 }.
}
