package com.ashoo;

import com.ashoo.briefing.BriefingDisclaimerInjector;
import com.ashoo.briefing.BriefingService;
import com.ashoo.briefing.BriefingService.BriefingResult;
import com.ashoo.reminder.*;
import com.ashoo.reminder.ReminderEngine.ReminderResult;
import com.ashoo.storage.entity.Medication;
import com.ashoo.storage.entity.ReminderRule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for M5 — consent gating, reminder firing, and briefing safety, against
 * a real TimescaleDB.
 *
 * Verifies the milestone's definition of done end to end: the consent gate blocks before
 * acceptance, a reminder fires (carrying the disclaimer) once consent + a rule exist, and
 * a briefing is generated with the mandatory disclaimer present. With no API key in the
 * test environment, the briefing exercises the deterministic fallback path.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class M5BriefingAndReminderTest {

    @Autowired private ConsentService consentService;
    @Autowired private MedicationService medicationService;
    @Autowired private ReminderRuleService reminderRuleService;
    @Autowired private ReminderEngine reminderEngine;
    @Autowired private BriefingService briefingService;

    @Test
    void consentGate_blocksBeforeAcceptance() {
        String user = "m5-noconsent";
        // No consent recorded for this user → every reminder/medication path must throw.
        assertThatThrownBy(() -> reminderEngine.evaluateReminders(user, 90, LocalTime.NOON))
                .isInstanceOf(ConsentRequiredException.class);
        assertThatThrownBy(() -> medicationService.list(user))
                .isInstanceOf(ConsentRequiredException.class);
        assertThatThrownBy(() ->
                reminderRuleService.create(user, 70, "note", null, null, null))
                .isInstanceOf(ConsentRequiredException.class);
    }

    @Test
    void afterConsent_reminderFiresWithDisclaimer() {
        String user = "m5-reminder-user";
        consentService.recordConsent(user);

        Medication med = medicationService.register(user, "Ventolin inhaler",
                MedicationType.INHALER, "blue rescue inhaler");
        ReminderRule rule = reminderRuleService.create(user, 70.0,
                "Carry your inhaler today", med.getId(), null, null);
        assertThat(rule.getId()).isNotNull();

        // Score above threshold → rule fires.
        List<ReminderResult> fired = reminderEngine.evaluateReminders(user, 85.0, LocalTime.NOON);
        assertThat(fired).hasSize(1);
        ReminderResult r = fired.getFirst();
        assertThat(r.userNote()).isEqualTo("Carry your inhaler today");
        assertThat(r.medicationName()).isEqualTo("Ventolin inhaler");
        assertThat(r.disclaimer()).isEqualTo(ReminderEngine.MANDATORY_DISCLAIMER);
        assertThat(r.disclaimer()).contains("not medical advice");

        // Score below threshold → nothing fires.
        assertThat(reminderEngine.evaluateReminders(user, 50.0, LocalTime.NOON)).isEmpty();
    }

    @Test
    void medicationUsageStats_areComputable() {
        String user = "m5-usage-user";
        consentService.recordConsent(user);
        medicationService.register(user, "Zyrtec", MedicationType.ANTIHISTAMINE, null);

        List<MedicationService.UsageStat> stats = medicationService.computeUsageStats(user);
        assertThat(stats).hasSize(1);
        assertThat(stats.getFirst().name()).isEqualTo("Zyrtec");
        assertThat(stats.getFirst().usesLast7Days()).isZero(); // no symptom logs reference it
    }

    @Test
    void briefing_isGeneratedWithMandatoryDisclaimer() {
        String user = "m5-briefing-user";
        // No correlation model for this user → builder uses the "keep logging" path,
        // and with no API key the fallback template is used. Disclaimer must still be present.
        BriefingResult result = briefingService.generateBriefing(user, false);

        assertThat(result.text()).isNotBlank();
        assertThat(result.text()).endsWith(BriefingDisclaimerInjector.DISCLAIMER);
        assertThat(result.source()).isEqualTo("fallback"); // no ANTHROPIC_API_KEY in tests
    }

    @Test
    void briefing_today_isCachedWithinTheSameDay() {
        String user = "m5-cache-user";
        BriefingResult first = briefingService.getTodayBriefing(user, false);
        BriefingResult second = briefingService.getTodayBriefing(user, false);

        assertThat(first.text()).isEqualTo(second.text());
        // First generates (fallback), second reuses the stored same-day briefing.
        assertThat(second.source()).isEqualTo("cached");
    }
}
