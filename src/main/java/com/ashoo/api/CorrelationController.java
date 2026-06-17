package com.ashoo.api;

import com.ashoo.api.dto.CorrelationResultResponse;
import com.ashoo.api.dto.MismatchDayResponse;
import com.ashoo.correlation.CorrelationService;
import com.ashoo.correlation.CorrelationService.CorrelationSummary;
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

    private static final String DEFAULT_USER = "ashoo-user";

    private final CorrelationService correlationService;

    public CorrelationController(CorrelationService correlationService) {
        this.correlationService = correlationService;
    }

    /**
     * Recomputes all correlations for the default user from their full history.
     *
     * @return a summary of the run: factors kept, symptom days used, confidence, timing
     */
    @PostMapping("/compute")
    public CorrelationSummary compute() {
        return correlationService.compute(DEFAULT_USER);
    }

    /**
     * Returns the cached per-factor correlation results, strongest weight first.
     *
     * @return the factor breakdown with rho, threshold, weight, and confidence
     */
    @GetMapping("/results")
    public List<CorrelationResultResponse> results() {
        return correlationService.findResults(DEFAULT_USER).stream()
                .map(CorrelationResultResponse::from)
                .toList();
    }

    /**
     * Returns days where the model's score disagreed with the logged symptoms.
     *
     * @return mismatch days ordered by how surprising they are
     */
    @GetMapping("/mismatches")
    public List<MismatchDayResponse> mismatches() {
        return correlationService.findMismatchDays(DEFAULT_USER, DEFAULT_USER).stream()
                .map(MismatchDayResponse::from)
                .toList();
    }
}
