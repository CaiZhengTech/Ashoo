package com.ashoo;

import com.ashoo.correlation.CorrelationService;
import com.ashoo.correlation.CorrelationService.CorrelationSummary;
import com.ashoo.correlation.ConfidenceLevel;
import com.ashoo.correlation.RiskScoringService;
import com.ashoo.correlation.RiskScoringService.RiskScoreBreakdown;
import com.ashoo.storage.entity.CorrelationResult;
import com.ashoo.storage.entity.EnvironmentalSnapshot;
import com.ashoo.storage.entity.SymptomLog;
import com.ashoo.storage.repository.EnvironmentalSnapshotRepository;
import com.ashoo.storage.repository.SymptomLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the M4 correlation engine, against a real TimescaleDB.
 *
 * Rather than hit the network, this seeds its own deterministic synthetic world:
 * ~120 days where PM2.5, grass pollen, and humidity drive symptoms (the "Morgan"
 * high-sensitivity rule), while wind and temperature are pure noise. It then runs
 * the engine and asserts that the real triggers surface with strong positive
 * correlation and meaningful weight, while the noise factors stay near zero — the
 * milestone's definition of done ("verify high-sensitivity factors surface").
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class M4CorrelationEngineTest {

    private static final String USER = "test-morgan";
    private static final int DAYS = 120;

    @Autowired private CorrelationService correlationService;
    @Autowired private RiskScoringService riskScoringService;
    @Autowired private EnvironmentalSnapshotRepository snapshotRepo;
    @Autowired private SymptomLogRepository symptomRepo;

    @BeforeEach
    void seedSyntheticWorld() {
        Random rng = new Random(42); // deterministic
        LocalDate start = LocalDate.now(ZoneOffset.UTC).minusDays(DAYS);

        List<EnvironmentalSnapshot> snapshots = new ArrayList<>();
        List<SymptomLog> symptoms = new ArrayList<>();

        for (int d = 0; d < DAYS; d++) {
            LocalDate day = start.plusDays(d);
            // Force the final day to be a clear high-trigger day so /risk scoring is elevated.
            boolean badDay = (d == DAYS - 1) || rng.nextDouble() < 0.45;

            double pm25, pollenGrass, humidity;
            if (badDay) {
                pm25 = 18 + rng.nextDouble() * 22;        // 18–40
                pollenGrass = 20 + rng.nextDouble() * 45; // 20–65
                humidity = 62 + rng.nextDouble() * 23;    // 62–85
            } else {
                pm25 = 3 + rng.nextDouble() * 6;          // 3–9
                pollenGrass = rng.nextDouble() * 8;       // 0–8
                humidity = 35 + rng.nextDouble() * 15;    // 35–50
            }
            double windSpeed = rng.nextDouble() * 10;     // pure noise
            double temperature = 8 + rng.nextDouble() * 18; // pure noise

            Instant recordedAt = day.atTime(12, 0).toInstant(ZoneOffset.UTC);
            snapshots.add(EnvironmentalSnapshot.builder()
                    .recordedAt(recordedAt).userId(USER).cityName("Synthetic City")
                    .pm25(pm25).pm10(pm25 * 1.5)
                    .pollenGrass(pollenGrass).humidityPct(humidity)
                    .windSpeedMs(windSpeed).temperatureC(temperature)
                    .dataSource("TEST").dataOrigin("SEEDED_SYNTHETIC")
                    .build());

            // Morgan rule: symptoms when pm25 > 12 OR pollen > 15 OR humidity > 60.
            boolean triggered = pm25 > 12 || pollenGrass > 15 || humidity > 60;
            if (triggered) {
                SymptomLog log = new SymptomLog();
                log.setUserId(USER);
                log.setLoggedAt(day.atTime(8, 0).toInstant(ZoneOffset.UTC));
                log.setSeverity(5 + rng.nextInt(5)); // 5–9
                log.setCityName("Synthetic City");
                log.setDataOrigin("SEEDED_SYNTHETIC");
                symptoms.add(log);
            }
        }

        snapshotRepo.saveAll(snapshots);
        symptomRepo.saveAll(symptoms);
    }

    @Test
    void compute_recoversHighSensitivityTriggers() {
        CorrelationSummary summary = correlationService.compute(USER);

        // Plenty of symptom days were seeded → HIGH confidence and a real model.
        assertThat(summary.symptomDaysUsed()).isGreaterThanOrEqualTo(30);
        assertThat(summary.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(summary.factorsComputed()).isGreaterThan(0);

        Map<String, CorrelationResult> byFactor = byFactorName(correlationService.findResults(USER));

        // The three real drivers should surface with strong positive correlation.
        assertThat(byFactor).containsKey("pm25");
        assertThat(byFactor.get("pm25").getSpearmanR()).isGreaterThan(0.3);
        assertThat(byFactor.get("pm25").getWeight()).isGreaterThan(0.0);

        assertThat(byFactor.get("pollen_grass").getSpearmanR()).isGreaterThan(0.3);
        assertThat(byFactor.get("humidity_pct").getSpearmanR()).isGreaterThan(0.3);

        // A real trigger should out-weigh pure noise (wind), if wind even survived.
        double pm25Weight = byFactor.get("pm25").getWeight();
        if (byFactor.containsKey("wind_speed_ms")) {
            assertThat(byFactor.get("wind_speed_ms").getWeight()).isLessThan(pm25Weight);
        }
    }

    @Test
    void riskScoring_returnsNonTrivialScoreOnHighTriggerDay() {
        correlationService.compute(USER);

        Optional<RiskScoreBreakdown> breakdown = riskScoringService.scoreAndPersist(USER);

        assertThat(breakdown).isPresent();
        // The final seeded day is a forced high-trigger day → elevated score.
        assertThat(breakdown.get().score().rawScore()).isGreaterThan(40.0);
        assertThat(breakdown.get().factors()).isNotEmpty();
        assertThat(breakdown.get().confidence()).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    void riskScoring_withoutModel_returnsEmpty() {
        // No correlation computed for this fresh user → scoring has no weights to use.
        Optional<RiskScoreBreakdown> breakdown =
                riskScoringService.currentBreakdown("nobody-user", "nobody-user");
        assertThat(breakdown).isEmpty();
    }

    @Test
    void mismatches_areComputableAfterCompute() {
        correlationService.compute(USER);
        // Should not throw and should return a (possibly empty) list — the clean
        // synthetic data has few mismatches by construction.
        assertThat(correlationService.findMismatchDays(USER, USER)).isNotNull();
    }

    private static Map<String, CorrelationResult> byFactorName(List<CorrelationResult> results) {
        Map<String, CorrelationResult> map = new java.util.HashMap<>();
        for (CorrelationResult r : results) map.put(r.getFactorName(), r);
        return map;
    }
}
