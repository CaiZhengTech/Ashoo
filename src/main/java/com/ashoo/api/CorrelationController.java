package com.ashoo.api;

import com.ashoo.api.dto.CorrelationResultResponse;
import com.ashoo.api.dto.MismatchDayResponse;
import com.ashoo.common.DemoUsers;
import com.ashoo.correlation.CorrelationService;
import com.ashoo.correlation.CorrelationService.CorrelationSummary;
import com.ashoo.correlation.RiskScoringService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Endpoints for the correlation engine — recomputing the model and inspecting it.
 *
 * The results and mismatch endpoints are the transparency surface of Ashoo: they
 * let the user (and a curious recruiter) see exactly which factors the engine
 * learned, how strong each relationship is, and where the model disagreed with
 * reality. Nothing here is a black box.
 */
@RestController
@RequestMapping("/api/v1/correlation")
public class CorrelationController {

    /** Days of trend to backfill, covering the dashboard's longest range (6 months);
     *  naturally capped by however much environmental history exists. */
    private static final int TREND_DAYS = 180;

    private final CorrelationService correlationService;
    private final RiskScoringService riskScoringService;

    public CorrelationController(CorrelationService correlationService,
                                 RiskScoringService riskScoringService) {
        this.correlationService = correlationService;
        this.riskScoringService = riskScoringService;
    }

    /**
     * Recomputes all correlations for the default user, then rebuilds their daily risk
     * trend so the dashboard chart reflects the new model.
     *
     * Backfilling here (rather than waiting for the hourly scorer to accumulate points)
     * means a freshly seeded or re-logged user immediately sees a real multi-day trend
     * instead of a single "now" point. The hourly scheduler is untouched — it still
     * appends one live point per run on top of this baseline.
     *
     * @return a summary of the run: factors kept, symptom days used, confidence, timing
     */
    @PostMapping("/compute")
    public CorrelationSummary compute(@RequestParam(required = false) String user) {
        String userId = DemoUsers.resolve(user);
        CorrelationSummary summary = correlationService.computeAndStore(userId, DemoUsers.envFor(userId));
        riskScoringService.backfillHistory(userId, DemoUsers.envFor(userId), TREND_DAYS);
        return summary;
    }

    /**
     * Returns the cached per-factor correlation results, strongest weight first.
     *
     * @param user optional persona to view (default user when omitted/unknown)
     * @return the factor breakdown with rho, threshold, weight, and confidence
     */
    @GetMapping("/results")
    public List<CorrelationResultResponse> results(@RequestParam(required = false) String user) {
        return correlationService.findResults(DemoUsers.resolve(user)).stream()
                .map(CorrelationResultResponse::from)
                .toList();
    }

    /**
     * Returns days where the model's score disagreed with the logged symptoms.
     *
     * @param user optional persona to view (default user when omitted/unknown)
     * @return mismatch days ordered by how surprising they are
     */
    @GetMapping("/mismatches")
    public List<MismatchDayResponse> mismatches(@RequestParam(required = false) String user) {
        String userId = DemoUsers.resolve(user);
        return correlationService.findMismatchDays(userId, DemoUsers.envFor(userId)).stream()
                .map(MismatchDayResponse::from)
                .toList();
    }
}
