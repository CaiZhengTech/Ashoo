package com.ashoo.reminder;

import com.ashoo.storage.entity.ReminderRule;
import com.ashoo.storage.repository.ReminderRuleRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/**
 * Consent-gated CRUD for the user's reminder rules.
 *
 * Like {@link MedicationService}, every method passes through {@link ConsentGuard}.
 * The service stores the user's threshold, note, and optional time window verbatim —
 * it neither writes the note for them nor attaches any disclaimer here (the disclaimer
 * is appended only at evaluation time, so it can't be edited out of a stored rule).
 */
@Service
public class ReminderRuleService {

    private final ReminderRuleRepository ruleRepo;
    private final ConsentGuard consentGuard;

    public ReminderRuleService(ReminderRuleRepository ruleRepo, ConsentGuard consentGuard) {
        this.ruleRepo = ruleRepo;
        this.consentGuard = consentGuard;
    }

    /**
     * Creates a reminder rule.
     *
     * @param userId       the owner
     * @param threshold    risk-score threshold (0–100) at/above which the rule fires
     * @param userNote     the note the user wrote, echoed back when the rule fires
     * @param medicationId optional linked medication id (may be null)
     * @param windowStart  optional time-window start (null = always active)
     * @param windowEnd    optional time-window end (null = always active)
     * @return the persisted rule
     * @throws ConsentRequiredException if the user has not consented
     */
    public ReminderRule create(String userId, double threshold, String userNote,
                               Long medicationId, LocalTime windowStart, LocalTime windowEnd) {
        consentGuard.requireConsent(userId);
        ReminderRule rule = new ReminderRule();
        rule.setUserId(userId);
        rule.setRiskScoreThreshold(threshold);
        rule.setUserNote(userNote);
        rule.setMedicationId(medicationId);
        rule.setTimeWindowStart(windowStart);
        rule.setTimeWindowEnd(windowEnd);
        rule.setIsActive(true);
        rule.setCreatedAt(Instant.now());
        return ruleRepo.save(rule);
    }

    /**
     * Lists the user's active reminder rules.
     *
     * @param userId the owner
     * @return active rules, lowest threshold first
     * @throws ConsentRequiredException if the user has not consented
     */
    public List<ReminderRule> list(String userId) {
        consentGuard.requireConsent(userId);
        return ruleRepo.findActiveByUserId(userId);
    }

    /**
     * Soft-deletes a reminder rule (marks it inactive).
     *
     * @param userId the owner
     * @param id     the rule id
     * @return true if found and deactivated, false if not found or not owned by the user
     * @throws ConsentRequiredException if the user has not consented
     */
    public boolean remove(String userId, Long id) {
        consentGuard.requireConsent(userId);
        return ruleRepo.findById(id)
                .filter(r -> userId.equals(r.getUserId()))
                .map(r -> {
                    r.setIsActive(false);
                    ruleRepo.save(r);
                    return true;
                })
                .orElse(false);
    }
}
