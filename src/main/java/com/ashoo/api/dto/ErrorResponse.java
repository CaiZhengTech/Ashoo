package com.ashoo.api.dto;

import java.time.Instant;

/**
 * The consistent error body returned for every handled failure.
 *
 * A single shape across the whole API means clients can parse failures uniformly instead
 * of guessing at ad-hoc messages. The fields mirror the familiar Spring Boot error format
 * (timestamp/status/error/message/path) so existing tooling and developers feel at home.
 *
 * @param timestamp when the error occurred
 * @param status    the HTTP status code
 * @param error     the HTTP status reason phrase (e.g. "Bad Request")
 * @param message   a human-readable explanation safe to show a client
 * @param path      the request path that failed
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path
) {}
