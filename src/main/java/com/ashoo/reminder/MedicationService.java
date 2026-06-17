package com.ashoo.reminder;

import com.ashoo.storage.entity.Medication;
import com.ashoo.storage.entity.SymptomLog;
import com.ashoo.storage.repository.MedicationRepository;
import com.ashoo.storage.repository.SymptomLogRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Consent-gated management of the user's own medications, plus usage-pattern stats.
 *
 * Every method runs through {@link ConsentGuard} first, so there is no way to touch
 * medication data without recorded consent. The service stores exactly what the user
 * enters and never suggests or modifies a medication — Ashoo's role is to echo, not advise.
 */
@Service
public class MedicationService {

    private static final int USAGE_WINDOW_DAYS = 90;
    private static final int RECENT_WINDOW_DAYS = 7;

    private final MedicationRepository medicationRepo;
    private final SymptomLogRepository symptomRepo;
    private final ConsentGuard consentGuard;

    public MedicationService(MedicationRepository medicationRepo,
                             SymptomLogRepository symptomRepo,
                             ConsentGuard consentGuard) {
        this.medicationRepo = medicationRepo;
        this.symptomRepo = symptomRepo;
        this.consentGuard = consentGuard;
    }

    /**
     * Registers a medication the user typed in themselves.
     *
     * @param userId the owner
     * @param name   the user's own medication name (e.g. "Ventolin inhaler")
     * @param type   the category chosen from the fixed dropdown
     * @param notes  optional free-text notes
     * @return the persisted medication
     * @throws ConsentRequiredException if the user has not consented
     */
    public Medication register(String userId, String name, MedicationType type, String notes) {
        consentGuard.requireConsent(userId);
        Medication med = new Medication();
        med.setUserId(userId);
        med.setName(name);
        med.setMedType(type.name());
        med.setNotes(notes);
        med.setIsActive(true);
        med.setCreatedAt(Instant.now());
        return medicationRepo.save(med);
    }

    /**
     * Lists the user's active medications.
     *
     * @param userId the owner
     * @return active medications, newest first
     * @throws ConsentRequiredException if the user has not consented
     */
    public List<Medication> list(String userId) {
        consentGuard.requireConsent(userId);
        return medicationRepo.findActiveByUserId(userId);
    }

    /**
     * Soft-deletes a medication (marks inactive) so symptom-log references stay valid.
     *
     * @param userId the owner
     * @param id     the medication id
     * @return true if found and deactivated, false if not found or not owned by the user
     * @throws ConsentRequiredException if the user has not consented
     */
    public boolean remove(String userId, Long id) {
        consentGuard.requireConsent(userId);
        return medicationRepo.findById(id)
                .filter(m -> userId.equals(m.getUserId()))
                .map(m -> {
                    m.setIsActive(false);
                    medicationRepo.save(m);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Computes per-medication usage stats: recent count vs. longer-term weekly average.
     *
     * Surfaces patterns like "rescue inhaler used 4× this week vs. a 1.2/week average,"
     * which is observational only — it describes the user's own logged behavior and never
     * suggests changing it. Usage is read from the {@code medications_used} arrays on the
     * user's symptom logs over the trailing window.
     *
     * @param userId the owner
     * @return one stat per active medication
     * @throws ConsentRequiredException if the user has not consented
     */
    public List<UsageStat> computeUsageStats(String userId) {
        consentGuard.requireConsent(userId);
        Instant now = Instant.now();
        Instant from = now.minus(Duration.ofDays(USAGE_WINDOW_DAYS));
        Instant recentFrom = now.minus(Duration.ofDays(RECENT_WINDOW_DAYS));

        List<SymptomLog> logs = symptomRepo.findByDateRange(userId, from, now);

        Map<Long, Integer> totalCounts = new HashMap<>();
        Map<Long, Integer> recentCounts = new HashMap<>();
        for (SymptomLog log : logs) {
            if (log.getMedicationsUsed() == null) continue;
            boolean recent = log.getLoggedAt().isAfter(recentFrom);
            for (Long medId : log.getMedicationsUsed()) {
                totalCounts.merge(medId, 1, Integer::sum);
                if (recent) recentCounts.merge(medId, 1, Integer::sum);
            }
        }

        double weeks = USAGE_WINDOW_DAYS / 7.0;
        List<UsageStat> stats = new ArrayList<>();
        for (Medication med : medicationRepo.findActiveByUserId(userId)) {
            int total = totalCounts.getOrDefault(med.getId(), 0);
            int recent = recentCounts.getOrDefault(med.getId(), 0);
            double weeklyAvg = total / weeks;
            stats.add(new UsageStat(med.getId(), med.getName(), med.getMedType(),
                    recent, Math.round(weeklyAvg * 10.0) / 10.0));
        }
        return stats;
    }

    /**
     * Looks up a medication by id without the consent gate — for internal resolution
     * (e.g. naming a reminder's linked medication), not for user-facing management.
     *
     * @param id the medication id
     * @return the medication, or empty if not found
     */
    Optional<Medication> findByIdInternal(Long id) {
        return medicationRepo.findById(id);
    }

    /**
     * A single medication's usage pattern.
     *
     * @param medicationId    the medication id
     * @param name            the user's medication name
     * @param medType         the category
     * @param usesLast7Days   how many logged uses in the last 7 days
     * @param weeklyAverage90d average uses per week over the last 90 days
     */
    public record UsageStat(Long medicationId, String name, String medType,
                            int usesLast7Days, double weeklyAverage90d) {}
}
