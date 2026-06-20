package com.ashoo.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Type-safe binding for all {@code ashoo.*} configuration properties.
 *
 * Spring Boot's relaxed binding maps YAML keys like {@code interval-ms}
 * to Java fields like {@code intervalMs} automatically. Using a record
 * makes the properties immutable after construction, preventing accidental
 * mutation of configuration at runtime.
 */
@ConfigurationProperties(prefix = "ashoo")
public record AshooProperties(
        LocationConfig defaultLocation,
        PollingConfig polling,
        OpenAqConfig openaq,
        AirNowConfig airnow,
        AnthropicConfig anthropic,
        CorrelationConfig correlation,
        RetentionConfig retention
) {
    /** A geographic location with coordinates and display name. */
    public record LocationConfig(double latitude, double longitude,
                                  String city, String country) {}

    /** Polling interval for the ingestion scheduler. */
    public record PollingConfig(long intervalMs) {}

    /** OpenAQ API connection settings. */
    public record OpenAqConfig(String apiKey, String baseUrl, int rateLimitPerMinute) {}

    /** AirNow API connection settings. */
    public record AirNowConfig(String apiKey, String baseUrl) {}

    /** Anthropic Claude API settings for daily briefings. */
    public record AnthropicConfig(String apiKey, String model, int maxTokens) {}

    /** Correlation engine tuning parameters. */
    public record CorrelationConfig(int minSymptomDays, List<Integer> lagWindowsHours,
                                     double ewmaLambda, double alertOnThreshold,
                                     double alertOffThreshold) {}

    /** Data retention periods (human-readable strings like "6 months"). */
    public record RetentionConfig(String rawSnapshots, String riskScores) {}
}
