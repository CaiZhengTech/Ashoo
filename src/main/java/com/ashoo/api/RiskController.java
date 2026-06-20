package com.ashoo.api;

import com.ashoo.api.dto.RiskCurrentResponse;
import com.ashoo.api.dto.RiskHistoryPointResponse;
import com.ashoo.common.DemoUsers;
import com.ashoo.correlation.RiskScoringService;
import com.ashoo.storage.repository.RiskScoreHistoryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Endpoints for the Personal Risk Index — the current score and its history.
 *
 * {@code /current} computes a live, non-persisted score (so repeated dashboard
 * refreshes don't pollute the trend series), while {@code /history} returns the
 * persisted hourly readings written by the scheduler for the trend chart.
 */
@RestController
@RequestMapping("/api/v1/risk")
public class RiskController {

    private final RiskScoringService riskScoringService;
    private final RiskScoreHistoryRepository riskHistoryRepo;

    public RiskController(RiskScoringService riskScoringService,
                          RiskScoreHistoryRepository riskHistoryRepo) {
        this.riskScoringService = riskScoringService;
        this.riskHistoryRepo = riskHistoryRepo;
    }

    /**
     * Returns the current Personal Risk Index with its full factor breakdown.
     *
     * @param user optional persona to view (default user when omitted/unknown)
     * @return the score and breakdown, or 409 Conflict if the model has not been
     *         computed yet (no snapshot ingested, or /correlation/compute never run)
     */
    @GetMapping("/current")
    public ResponseEntity<RiskCurrentResponse> current(
            @RequestParam(required = false) String user) {
        String userId = DemoUsers.resolve(user);
        return riskScoringService.currentBreakdown(userId, DemoUsers.envFor(userId))
                .map(RiskCurrentResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(409).build());
    }

    /**
     * Returns the persisted risk-score history within a date range, oldest first.
     *
     * @param from inclusive start (ISO-8601)
     * @param to   inclusive end (ISO-8601)
     * @param user optional persona to view (default user when omitted/unknown)
     * @return the trend points for charting
     */
    @GetMapping("/history")
    public List<RiskHistoryPointResponse> history(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) String user) {
        return riskHistoryRepo.findByDateRange(DemoUsers.resolve(user), from, to).stream()
                .map(RiskHistoryPointResponse::from)
                .toList();
    }
}
