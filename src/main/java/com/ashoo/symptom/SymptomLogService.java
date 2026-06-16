package com.ashoo.symptom;

import com.ashoo.storage.entity.RecalibrationEvent;
import com.ashoo.storage.entity.SymptomLog;
import com.ashoo.storage.repository.RecalibrationEventRepository;
import com.ashoo.storage.repository.SymptomLogRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Business logic for symptom log entries.
 *
 * Symptom logs are the label data for the correlation engine. Every edit
 * to a past entry is significant — the engine must recompute to reflect
 * the correction. This service creates a RecalibrationEvent on every update
 * so the user can see when and why the model recalibrated.
 */
@Service
public class SymptomLogService {

    private static final String DEFAULT_USER = "ashoo-user";

    private final SymptomLogRepository symptomRepo;
    private final RecalibrationEventRepository recalibrationRepo;

    public SymptomLogService(SymptomLogRepository symptomRepo,
                              RecalibrationEventRepository recalibrationRepo) {
        this.symptomRepo = symptomRepo;
        this.recalibrationRepo = recalibrationRepo;
    }

    /**
     * Creates a new symptom log entry.
     *
     * Sets userId and defaults before persisting. Severity is validated
     * at the controller layer (0–10 constraint), not here — this service
     * trusts its callers are using the validated DTO.
     *
     * @param log the entry to create
     * @return the persisted entry with its generated id
     */
    public SymptomLog create(SymptomLog log) {
        log.setUserId(DEFAULT_USER);
        if (log.getLoggedAt() == null) log.setLoggedAt(Instant.now());
        if (log.getDataOrigin() == null) log.setDataOrigin("REAL");
        return symptomRepo.save(log);
    }

    /**
     * Returns all symptom log entries within a date range.
     *
     * @param from inclusive start
     * @param to   inclusive end
     * @return matching entries, newest first
     */
    public List<SymptomLog> findByDateRange(Instant from, Instant to) {
        return symptomRepo.findByDateRange(DEFAULT_USER, from, to);
    }

    /**
     * Returns all symptom log entries for the default user.
     *
     * @return all entries, newest first
     */
    public List<SymptomLog> findAll() {
        return symptomRepo.findAllByUserId(DEFAULT_USER);
    }

    /**
     * Updates an existing symptom log entry and creates a recalibration event.
     *
     * The recalibration event records that the model must recompute because
     * historical data changed. In M4 the correlation engine will subscribe
     * to these events to trigger recomputation automatically.
     *
     * @param id      the entry to update
     * @param updated the new field values
     * @return the updated entry, or empty if not found
     */
    public Optional<SymptomLog> update(Long id, SymptomLog updated) {
        return symptomRepo.findByIdAndUserId(id, DEFAULT_USER)
                .map(existing -> {
                    existing.setLoggedAt(updated.getLoggedAt() != null
                            ? updated.getLoggedAt() : existing.getLoggedAt());
                    existing.setSeverity(updated.getSeverity() != null
                            ? updated.getSeverity() : existing.getSeverity());
                    existing.setNotes(updated.getNotes());
                    existing.setLocationId(updated.getLocationId());
                    existing.setCityName(updated.getCityName());
                    existing.setMedicationsUsed(updated.getMedicationsUsed());

                    int rows = symptomRepo.update(existing);
                    if (rows > 0) {
                        createRecalibrationEvent(id, "symptom log edited");
                    }
                    return existing;
                });
    }

    /**
     * Deletes a symptom log entry.
     *
     * Also creates a recalibration event because removing a label day
     * changes the correlation model's training data.
     *
     * @param id the entry id to delete
     * @return true if deleted, false if not found
     */
    public boolean delete(Long id) {
        int rows = symptomRepo.deleteByIdAndUserId(id, DEFAULT_USER);
        if (rows > 0) {
            createRecalibrationEvent(id, "symptom log deleted");
            return true;
        }
        return false;
    }

    /**
     * Returns the count of days with logged symptoms — used to determine
     * correlation confidence level (LOW < 10, MEDIUM 10–29, HIGH 30+).
     *
     * @return number of distinct symptom days
     */
    public int countSymptomDays() {
        return symptomRepo.countSymptomDays(DEFAULT_USER);
    }

    private void createRecalibrationEvent(Long symptomLogId, String reason) {
        RecalibrationEvent event = new RecalibrationEvent();
        event.setUserId(DEFAULT_USER);
        event.setTriggeredAt(Instant.now());
        event.setReason(reason);
        event.setSymptomLogId(symptomLogId);
        recalibrationRepo.save(event);
    }
}
