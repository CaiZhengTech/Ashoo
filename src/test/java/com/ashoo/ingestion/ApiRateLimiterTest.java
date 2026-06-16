package com.ashoo.ingestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the token-bucket rate limiter.
 *
 * Verifies permit acquisition, exhaustion, and that unregistered APIs
 * are allowed by default (fail-open for non-rate-limited APIs like Open-Meteo).
 */
class ApiRateLimiterTest {

    @Test
    void acquireSucceedsWhenPermitsAvailable() throws InterruptedException {
        ApiRateLimiter limiter = new ApiRateLimiter();
        limiter.register("test-api", 5);

        assertThat(limiter.acquire("test-api")).isTrue();
        assertThat(limiter.availablePermits("test-api")).isEqualTo(4);
    }

    @Test
    void tryAcquireReturnsFalseWhenExhausted() {
        ApiRateLimiter limiter = new ApiRateLimiter();
        limiter.register("test-api", 2);

        assertThat(limiter.tryAcquire("test-api")).isTrue();
        assertThat(limiter.tryAcquire("test-api")).isTrue();
        assertThat(limiter.tryAcquire("test-api")).isFalse();
    }

    @Test
    void unregisteredApiAllowedByDefault() throws InterruptedException {
        ApiRateLimiter limiter = new ApiRateLimiter();
        assertThat(limiter.acquire("unknown-api")).isTrue();
    }

    @Test
    void availablePermitsReturnsNegativeOneForUnregistered() {
        ApiRateLimiter limiter = new ApiRateLimiter();
        assertThat(limiter.availablePermits("unknown")).isEqualTo(-1);
    }
}
