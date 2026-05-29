package com.fbtracker.backend;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Payload for the public Fithub dashboard. Each category is a "repo" with a
 * per-day map of values; {@code primaryCategory} drives the main contribution heatmap.
 */
public record FithubResponse(
        LocalDate start,
        LocalDate end,
        String primaryCategory,
        List<Category> categories) {

    public record Category(
            String key,
            String label,
            String unit,
            String aggregation,   // "sum" | "value"
            boolean primary,
            Map<LocalDate, Double> days) {
    }
}
