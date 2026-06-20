package com.ashoo.ingestion;

import com.ashoo.correlation.RiskScoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the ingestion cycle on a fixed hourly schedule, then re-scores risk.
 *
 * Uses {@code fixedDelay} (not {@code fixedRate}) to ensure one cycle fully
 * completes before the next begins — preventing overlapping polls if an API
 * call is slow. With {@code spring.threads.virtual.enabled=true}, Spring runs
 * this on a virtual thread, so the blocking HTTP calls inside IngestionService
 * don't waste OS threads.
 *
 * Scoring runs right after each successful ingest so the Personal Risk Index and
 * its history stay in lock-step with the freshest snapshot.
 */
@Component
public class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

    private static final String DEFAULT_USER = "ashoo-user";

    private final IngestionService ingestionService;
    private final RiskScoringService riskScoringService;

    public IngestionScheduler(IngestionService ingestionService,
                              RiskScoringService riskScoringService) {
        this.ingestionService = ingestionService;
        this.riskScoringService = riskScoringService;
    }

    /**
     * Runs the full ingestion cycle for the default location, then scores risk.
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
            scoreRisk();
        } catch (Exception e) {
            log.error("Ingestion cycle failed", e);
        }
    }

    /**
     * Re-scores the Personal Risk Index after ingestion and persists it.
     *
     * Isolated in its own try/catch so a scoring failure (e.g. no correlation model
     * computed yet) never masks or rolls back a successful data ingest.
     */
    private void scoreRisk() {
        try {
            riskScoringService.scoreAndPersist(DEFAULT_USER)
                    .ifPresentOrElse(
                            b -> log.info("Risk scored: {} ({})",
                                    Math.round(b.score().smoothedScore()), b.score().level().getLabel()),
                            () -> log.debug("Risk scoring skipped, no snapshot or model yet"));
        } catch (Exception e) {
            log.error("Risk scoring failed (ingest still succeeded)", e);
        }
    }
}
