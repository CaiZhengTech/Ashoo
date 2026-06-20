package com.ashoo.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Token-bucket rate limiter for outbound API calls.
 *
 * Each named API gets a {@link Semaphore} initialized to its per-minute limit.
 * Before calling an API, the caller acquires a permit; a scheduled task releases
 * all permits every 60 seconds, refilling the bucket. This prevents concurrent
 * virtual threads from overwhelming a rate-limited API like OpenAQ (60 req/min).
 *
 * Open-Meteo has no rate limit, so it is not registered here. OpenAQ is registered
 * with 55 permits (leaving 5 as buffer below the hard 60/min ceiling).
 */
@Component
public class ApiRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ApiRateLimiter.class);

    private final ConcurrentHashMap<String, Semaphore> limiters = new ConcurrentHashMap<>();

    /**
     * Registers a named API with a per-minute request limit.
     *
     * Call this once at startup for each rate-limited API. The semaphore is
     * initialized to {@code permitsPerMinute} and refilled every 60 seconds.
     *
     * @param apiName           identifier for this API (e.g., "openaq")
     * @param permitsPerMinute  maximum requests per minute
     */
    public void register(String apiName, int permitsPerMinute) {
        limiters.put(apiName, new Semaphore(permitsPerMinute));
        log.info("Registered rate limiter for '{}': {} permits/min", apiName, permitsPerMinute);
    }

    /**
     * Acquires a rate-limit permit before an outbound API call.
     *
     * Blocks the current virtual thread until a permit is available. Because
     * virtual threads are cheap (~microseconds), blocking here does not waste
     * OS resources — it simply parks the virtual thread until the next refill.
     *
     * @param apiName identifier for the API (must have been registered)
     * @return true if the permit was acquired, false if the API is not registered
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean acquire(String apiName) throws InterruptedException {
        Semaphore semaphore = limiters.get(apiName);
        if (semaphore == null) {
            log.warn("No rate limiter registered for '{}', allowing request", apiName);
            return true;
        }
        semaphore.acquire();
        return true;
    }

    /**
     * Non-blocking attempt to acquire a permit.
     *
     * @param apiName identifier for the API
     * @return true if a permit was acquired immediately, false if none available
     */
    public boolean tryAcquire(String apiName) {
        Semaphore semaphore = limiters.get(apiName);
        if (semaphore == null) return true;
        return semaphore.tryAcquire();
    }

    /**
     * Refills all registered semaphores to their original capacity every 60 seconds.
     *
     * This is the "bucket refill" in the token-bucket pattern. We release all
     * permits rather than tracking initial capacity because the semaphore allows
     * releasing beyond the initial count — calling {@code release()} when no
     * permits are held simply increases availability.
     */
    @Scheduled(fixedRate = 60_000)
    void refillPermits() {
        limiters.forEach((name, semaphore) -> {
            int drained = semaphore.drainPermits();
            semaphore.release(drained + semaphore.availablePermits());
        });
    }

    /**
     * Returns the number of permits currently available for an API.
     * Useful for monitoring and testing.
     *
     * @param apiName identifier for the API
     * @return available permits, or -1 if not registered
     */
    public int availablePermits(String apiName) {
        Semaphore semaphore = limiters.get(apiName);
        return semaphore != null ? semaphore.availablePermits() : -1;
    }
}
