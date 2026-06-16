package com.ashoo.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the ingestion cycle on a fixed hourly schedule.
 *
 * Uses {@code fixedDelay} (not {@code fixedRate}) to ensure one cycle fully
 * completes before the next begins — preventing overlapping polls if an API
 * call is slow. With {@code spring.threads.virtual.enabled=true}, Spring runs
 * this on a virtual thread, so the blocking HTTP calls inside IngestionService
 * don't waste OS threads.
 */
@Component
public class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

    private final IngestionService ingestionService;

    public IngestionScheduler(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * Runs the full ingestion cycle for the default location.
     *
     * The delay is configured via {@code ashoo.polling.interval-ms} in
     * application.yml (default 3,600,000 ms = 1 hour). Using a property
     * reference allows changing the interval without recompiling.
     */
    @Scheduled(fixedDelayString = "${ashoo.polling.interval-ms}")
    public void runIngestionCycle() {
        log.info("Starting scheduled ingestion cycle");
        try {
            ingestionService.ingestDefaultLocation()
                    .ifPresentOrElse(
                            s -> log.info("Ingestion cycle complete: {} at {}",
                                    s.getCityName(), s.getRecordedAt()),
                            () -> log.warn("Ingestion cycle returned no data"));
        } catch (Exception e) {
            log.error("Ingestion cycle failed", e);
        }
    }
}
