package com.ashoo.reminder;

import com.ashoo.reminder.ReminderEngine.ReminderResult;
import com.ashoo.storage.entity.ReminderRule;
import com.ashoo.storage.repository.MedicationRepository;
import com.ashoo.storage.repository.ReminderRuleRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ReminderEngine}.
 *
 * The headline guarantees: consent is checked first, the disclaimer is always present
 * on a fired reminder, and time-window filtering (including overnight windows) works.
 */
class ReminderEngineTest {

    private final ReminderRuleRepository ruleRepo = mock(ReminderRuleRepository.class);
    private final MedicationRepository medicationRepo = mock(MedicationRepository.class);
    private final ConsentGuard consentGuard = mock(ConsentGuard.class);
    private final ReminderEngine engine = new ReminderEngine(ruleRepo, medicationRepo, consentGuard);

    private static ReminderRule rule(double threshold, String note, LocalTime start, LocalTime end) {
        ReminderRule r = new ReminderRule();
        r.setId(1L);
        r.setUserId("user");
        r.setRiskScoreThreshold(threshold);
        r.setUserNote(note);
        r.setTimeWindowStart(start);
        r.setTimeWindowEnd(end);
        r.setIsActive(true);
        return r;
    }

    @Test
    void consentCheckedBeforeAnyRuleIsRead() {
        doThrow(new ConsentRequiredException()).when(consentGuard).requireConsent("user");
        assertThatThrownBy(() -> engine.evaluateReminders("user", 90, LocalTime.NOON))
                .isInstanceOf(ConsentRequiredException.class);
        verify(ruleRepo, never()).findActiveByUserId(anyString());
    }

    @Test
    void firedReminder_alwaysCarriesTheDisclaimer() {
        when(ruleRepo.findActiveByUserId("user"))
                .thenReturn(List.of(rule(70, "Carry your inhaler today", null, null)));

        List<ReminderResult> results = engine.evaluateReminders("user", 75, LocalTime.NOON);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().userNote()).isEqualTo("Carry your inhaler today");
        assertThat(results.getFirst().disclaimer()).isEqualTo(ReminderEngine.MANDATORY_DISCLAIMER);
        assertThat(results.getFirst().disclaimer()).contains("not medical advice");
    }

    @Test
    void belowThreshold_doesNotFire() {
        when(ruleRepo.findActiveByUserId("user"))
                .thenReturn(List.of(rule(70, "note", null, null)));
        assertThat(engine.evaluateReminders("user", 60, LocalTime.NOON)).isEmpty();
    }

    @Test
    void atThreshold_fires() {
        when(ruleRepo.findActiveByUserId("user"))
                .thenReturn(List.of(rule(70, "note", null, null)));
        assertThat(engine.evaluateReminders("user", 70, LocalTime.NOON)).hasSize(1);
    }

    @Test
    void outsideDaytimeWindow_doesNotFire() {
        when(ruleRepo.findActiveByUserId("user"))
                .thenReturn(List.of(rule(50, "note", LocalTime.of(9, 0), LocalTime.of(17, 0))));
        // 20:00 is outside 09:00–17:00
        assertThat(engine.evaluateReminders("user", 80, LocalTime.of(20, 0))).isEmpty();
    }

    @Test
    void insideDaytimeWindow_fires() {
        when(ruleRepo.findActiveByUserId("user"))
                .thenReturn(List.of(rule(50, "note", LocalTime.of(9, 0), LocalTime.of(17, 0))));
        assertThat(engine.evaluateReminders("user", 80, LocalTime.of(12, 0))).hasSize(1);
    }

    @Test
    void overnightWindow_firesAcrossMidnight() {
        when(ruleRepo.findActiveByUserId("user"))
                .thenReturn(List.of(rule(50, "note", LocalTime.of(22, 0), LocalTime.of(6, 0))));
        // 23:00 is inside a 22:00–06:00 overnight window; 12:00 is not.
        assertThat(engine.evaluateReminders("user", 80, LocalTime.of(23, 0))).hasSize(1);
        assertThat(engine.evaluateReminders("user", 80, LocalTime.of(12, 0))).isEmpty();
    }
}
