package com.ashoo.api;

import com.ashoo.correlation.RiskScoringService;
import com.ashoo.reminder.ReminderEngine;
import com.ashoo.reminder.ReminderEngine.ReminderResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;
import java.util.List;

/**
 * Evaluates the user's reminder rules against the current risk score, right now.
 *
 * Pulls the live smoothed score, then asks the {@link ReminderEngine} which rules fire at
 * the current time. Consent is enforced inside the engine, so an un-consented user gets a
 * 403 here automatically. Every returned reminder carries the mandatory disclaimer.
 */
@RestController
@RequestMapping("/api/v1/reminders")
public class ReminderController {

    private static final String DEFAULT_USER = "ashoo-user";

    private final ReminderEngine reminderEngine;
    private final RiskScoringService riskScoringService;

    public ReminderController(ReminderEngine reminderEngine,
                             RiskScoringService riskScoringService) {
        this.reminderEngine = reminderEngine;
        this.riskScoringService = riskScoringService;
    }

    /**
     * Returns the reminders that fire for the current score at the current time.
     *
     * If no risk model exists yet, the score defaults to 0 (no rules fire) rather than
     * erroring — the consent gate still applies.
     *
     * @return triggered reminders, each with the disclaimer; empty if none fired
     */
    @GetMapping("/current")
    public List<ReminderResult> current() {
        double score = riskScoringService.currentBreakdown(DEFAULT_USER, DEFAULT_USER)
                .map(b -> b.score().smoothedScore())
                .orElse(0.0);
        return reminderEngine.evaluateReminders(DEFAULT_USER, score, LocalTime.now());
    }
}
