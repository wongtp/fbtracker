package com.fbtracker.backend;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fbtracker.backend.FithubResponse.Category;

/**
 * Assembles the Fithub dashboard payload from InfluxDB. Defines the category
 * ("repo") catalog and maps each to the appropriate per-day range query.
 */
@Service
public class FithubService {

    /** Aggregation strategy for a category's daily value. */
    private enum Agg { SUM, VALUE }

    private record CategoryDef(String key, String label, String unit,
                               String measurement, String field, Agg agg, boolean primary) {
    }

    private static final List<CategoryDef> CATALOG = List.of(
            new CategoryDef("calories", "Active Calories", "kcal", "calories_intraday", "count", Agg.SUM, true),
            new CategoryDef("steps", "Steps", "steps", "steps_intraday", "count", Agg.SUM, false),
            new CategoryDef("distance", "Distance", "mi", "distance_intraday", "count", Agg.SUM, false),
            new CategoryDef("sleep", "Sleep", "min", "sleep", "minutesAsleep", Agg.VALUE, false),
            new CategoryDef("restingHr", "Resting HR", "bpm", "heartrate", "resting", Agg.VALUE, false),
            new CategoryDef("spo2", "Blood Oxygen", "%", "oxygen", "avg", Agg.VALUE, false));

    private final InfluxQueryService queryService;

    public FithubService(InfluxQueryService queryService) {
        this.queryService = queryService;
    }

    public FithubResponse build(LocalDate start, LocalDate end) {
        List<Category> categories = CATALOG.stream()
                .map(def -> toCategory(def, start, end))
                .toList();

        String primary = CATALOG.stream()
                .filter(CategoryDef::primary)
                .map(CategoryDef::key)
                .findFirst()
                .orElse(CATALOG.get(0).key());

        return new FithubResponse(start, end, primary, categories);
    }

    private Category toCategory(CategoryDef def, LocalDate start, LocalDate end) {
        Map<LocalDate, Double> days = switch (def.agg()) {
            case SUM -> queryService.getDailySumRange(def.measurement(), def.field(), start, end);
            case VALUE -> queryService.getDailyValueRange(def.measurement(), def.field(), start, end);
        };
        return new Category(def.key(), def.label(), def.unit(),
                def.agg().name().toLowerCase(), def.primary(), days);
    }
}
