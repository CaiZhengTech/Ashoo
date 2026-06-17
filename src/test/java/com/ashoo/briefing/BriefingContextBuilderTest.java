package com.ashoo.briefing;

import com.ashoo.common.AshooProperties;
import com.ashoo.common.AshooProperties.LocationConfig;
import com.ashoo.correlation.RiskScoringService;
import com.ashoo.storage.entity.SymptomLog;
import com.ashoo.storage.repository.MedicationRepository;
import com.ashoo.storage.repository.SavedLocationRepository;
import com.ashoo.storage.repository.SymptomLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BriefingContextBuilder}.
 *
 * The headline safety check: a user's free-text symptom note must never make it into the
 * context that gets serialized and sent to the external API. We feed in a log carrying a
 * sensitive note and assert it appears nowhere in the serialized context.
 */
class BriefingContextBuilderTest {

    private final RiskScoringService riskScoringService = mock(RiskScoringService.class);
    private final SymptomLogRepository symptomRepo = mock(SymptomLogRepository.class);
    private final MedicationRepository medicationRepo = mock(MedicationRepository.class);
    private final SavedLocationRepository locationRepo = mock(SavedLocationRepository.class);

    private final AshooProperties props = new AshooProperties(
            new LocationConfig(42.12, -71.18, "Sharon, MA", "US"),
            null, null, null, null, null, null, null);

    private final BriefingContextBuilder builder = new BriefingContextBuilder(
            riskScoringService, symptomRepo, medicationRepo, locationRepo, props);

    @Test
    void freeTextNote_neverEntersTheContext() throws Exception {
        String secret = "SECRET-DIARY-felt-anxious-after-argument";

        SymptomLog log = new SymptomLog();
        log.setSeverity(6);
        log.setNotes(secret); // sensitive free text that must NOT be sent to the API
        log.setLoggedAt(Instant.now().minus(1, ChronoUnit.DAYS));

        when(riskScoringService.currentBreakdown(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(symptomRepo.countSymptomDays("user")).thenReturn(5);
        when(symptomRepo.findByDateRange(anyString(), any(), any())).thenReturn(List.of(log));
        when(medicationRepo.findActiveByUserId("user")).thenReturn(List.of());
        when(locationRepo.findActiveByUserId("user")).thenReturn(List.of());

        BriefingContext context = builder.buildContext("user");

        // The structured fields are present...
        assertThat(context.recentSymptomDays()).hasSize(1);
        assertThat(context.recentSymptomDays().getFirst().severity()).isEqualTo(6);
        assertThat(context.location()).isEqualTo("Sharon, MA");

        // ...but the note text appears nowhere in the serialized payload sent to the API.
        String json = new ObjectMapper().writeValueAsString(context);
        assertThat(json).doesNotContain(secret);
        assertThat(json).doesNotContain("SECRET-DIARY");
    }

    @Test
    void usesPrimaryLocationWhenAvailable() {
        when(riskScoringService.currentBreakdown(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(symptomRepo.countSymptomDays("user")).thenReturn(0);
        when(symptomRepo.findByDateRange(anyString(), any(), any())).thenReturn(List.of());
        when(medicationRepo.findActiveByUserId("user")).thenReturn(List.of());

        com.ashoo.storage.entity.SavedLocation primary = new com.ashoo.storage.entity.SavedLocation();
        primary.setCityName("Boston, MA");
        primary.setIsPrimary(true);
        when(locationRepo.findActiveByUserId("user")).thenReturn(List.of(primary));

        BriefingContext context = builder.buildContext("user");
        assertThat(context.location()).isEqualTo("Boston, MA");
    }
}
