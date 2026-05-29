package com.fbtracker.backend;

/**
 * Normalized oxygen-saturation (SpO2) data for a day.
 *
 * @param avg average SpO2 percentage for the day, or null if unavailable
 */
public record OxygenData(Double avg) {}
