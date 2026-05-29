package com.fbtracker.backend;

import java.time.Instant;

/** A single intraday sample: a value at a resolved instant. Provider-agnostic. */
public record IntradayPoint(Instant timestamp, double value) {}
