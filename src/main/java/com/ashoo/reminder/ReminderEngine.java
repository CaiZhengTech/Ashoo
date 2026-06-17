package com.ashoo.reminder;

import com.ashoo.storage.entity.Medication;
import com.ashoo.storage.entity.ReminderRule;
import com.ashoo.storage.repository.MedicationRepository;
import com.ashoo.storage.repository.ReminderRuleRepository;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Evaluates the user's reminder rules against the current risk score and time, and
 * returns any that fired — each carrying the mandatory disclaimer.
 *
 * Two safety properties are enforced structurally, not by convention:
 * <ol>
 *   <li><b>Consent first.</b> The method calls {@link ConsentGuard#requireConsent} before
 *       reading any rule, so a reminder can never surface without recorded consent.</li>
 *   <li><b>Disclaimer always.</b> The disclaimer is a compile-time constant appended to
 *       every result unconditionally. It is never stored, never loaded from config, and
 *       cannot be disabled — changing it requires a code review.</li>
 * </ol>
 *
 * The engine adds no content of its own beyond the disclaimer: a reminder is purely the
 * user's own note echoed back when their own condition is met.
 */
@Component
public class ReminderEngine {

    /**
     * The mandatory advisory disclaimer appended to every reminder. Defined here as a
     * constant by design — it must never be editable at runtime.
     */
    public static final String MANDATORY_DISCLAIMER =
            "⚠️ This is a suggestion based on your own settings, not medical advice. "
            + "Always carry your prescribed medication. Consult your doctor.";

    private final ReminderRuleRepository ruleRepo;
    private final MedicationRepository medicationRepo;
    private final ConsentGuard consentGuard;

    public ReminderEngine(ReminderRuleRepository ruleRepo,
                          MedicationRepository medicationRepo,
                          ConsentGuard consentGuard) {
        this.ruleRepo = ruleRepo;
        this.medicationRepo = medicationRepo;
        this.consentGuard = consentGuard;
    }

    /**
     * Returns the reminders that fire for the given score at the given time.
     *
     * A rule fires when the current score meets its threshold AND the current time falls
     * within the rule's window (or the rule has no window). Each fired reminder echoes the
     * user's note, names the linked medication if any, and appends the disclaimer.
     *
     * @param userId       the user whose rules to evaluate
     * @param currentScore the current smoothed Personal Risk Index (0–100)
     * @param currentTime  the current local time, for time-window filtering
     * @return triggered reminders (each with the disclaimer); empty if none fired
     * @throws ConsentRequiredException if the user has not accepted the disclaimer
     */
    public List<ReminderResult> evaluateReminders(String userId, double currentScore,
                                                  LocalTime currentTime) {
        consentGuard.requireConsent(userId);

        List<ReminderResult> triggered = new ArrayList<>();
        for (ReminderRule rule : ruleRepo.findActiveByUserId(userId)) {
            if (currentScore < rule.getRiskScoreThreshold()) continue;
            if (!withinWindow(currentTime, rule.getTimeWindowStart(), rule.getTimeWindowEnd())) continue;

            String medName = null;
            String medType = null;
            if (rule.getMedicationId() != null) {
                Optional<Medication> med = medicationRepo.findById(rule.getMedicationId());
                if (med.isPresent()) {
                    medName = med.get().getName();
                    medType = med.get().getMedType();
                }
            }

            triggered.add(new ReminderResult(
                    rule.getId(), rule.getRiskScoreThreshold(), rule.getUserNote(),
                    medName, medType, MANDATORY_DISCLAIMER));
        }
        return triggered;
    }

    /**
     * Tests whether a time falls within an optional window, handling overnight ranges.
     *
     * A null start or end means "always active." A window where start ≤ end is a normal
     * same-day range; a window where start &gt; end wraps past midnight (e.g. 22:00–06:00),
     * so we treat "after start OR before end" as inside.
     *
     * @param now   the current time
     * @param start window start, or null for always-on
     * @param end   window end, or null for always-on
     * @return true if {@code now} is within the window
     */
    private boolean withinWindow(LocalTime now, LocalTime start, LocalTime end) {
        if (start == null || end == null) return true;
        if (!start.isAfter(end)) {
            return !now.isBefore(start) && !now.isAfter(end); // start <= now <= end
        }
        return !now.isBefore(start) || !now.isAfter(end);     // overnight wrap
    }

    /**
     * One fired reminder: the user's own note plus the unconditional disclaimer.
     *
     * @param ruleId         the rule that fired
     * @param threshold      the threshold it crossed
     * @param userNote       the user's own note, echoed back
     * @param medicationName the linked medication name, or null if none
     * @param medicationType the linked medication category, or null if none
     * @param disclaimer     the mandatory disclaimer (always present)
     */
    public record ReminderResult(Long ruleId, double threshold, String userNote,
                                 String medicationName, String medicationType, String disclaimer) {}
}
