package com.fbtracker.backend;

import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, read-only API backing the Fithub dashboard at /fithub/.
 */
@RestController
@RequestMapping("/api/fithub")
public class FithubController {

    private final FithubService fithubService;
    private final ZoneId zone;

    public FithubController(FithubService fithubService, @Value("${app.timezone}") String timezone) {
        this.fithubService = fithubService;
        this.zone = ZoneId.of(timezone);
    }

    @GetMapping("/data")
    public FithubResponse data(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        LocalDate e = (end != null) ? end : LocalDate.now(zone);
        LocalDate s = (start != null) ? start : e.minusDays(364); // 365-day inclusive window
        return fithubService.build(s, e);
    }
}
