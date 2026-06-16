package com.ashoo.api;

import com.ashoo.api.dto.SymptomLogRequest;
import com.ashoo.api.dto.SymptomLogResponse;
import com.ashoo.storage.entity.SymptomLog;
import com.ashoo.symptom.SymptomLogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * CRUD endpoints for symptom log entries.
 *
 * These are the label data for the correlation engine. Every entry represents
 * a day the user noted their respiratory health. Severity must be 0–10;
 * this controller validates that constraint before passing to the service.
 */
@RestController
@RequestMapping("/api/v1/symptoms")
public class SymptomController {

    private final SymptomLogService symptomService;

    public SymptomController(SymptomLogService symptomService) {
        this.symptomService = symptomService;
    }

    /**
     * Logs a new symptom entry.
     *
     * @param req the symptom details (severity 0–10 required)
     * @return the created entry with 201 Created
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody SymptomLogRequest req) {
        if (req.severity() == null || req.severity() < 0 || req.severity() > 10) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "severity must be between 0 and 10"));
        }
        SymptomLog log = toEntity(req);
        SymptomLog saved = symptomService.create(log);
        return ResponseEntity
                .created(URI.create("/api/v1/symptoms/" + saved.getId()))
                .body(SymptomLogResponse.from(saved));
    }

    /**
     * Returns symptom log entries, optionally filtered by date range.
     *
     * If no date range is provided, returns all entries.
     *
     * @param from optional start (ISO-8601)
     * @param to   optional end (ISO-8601)
     * @return matching entries, newest first
     */
    @GetMapping
    public List<SymptomLogResponse> list(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        if (from != null && to != null) {
            return symptomService.findByDateRange(from, to).stream()
                    .map(SymptomLogResponse::from).toList();
        }
        return symptomService.findAll().stream()
                .map(SymptomLogResponse::from).toList();
    }

    /**
     * Edits a past symptom log entry.
     *
     * Any edit triggers a recalibration event — the correlation engine
     * must recompute because its training data changed.
     *
     * @param id  the entry to edit
     * @param req the updated values
     * @return the updated entry, or 404 if not found
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody SymptomLogRequest req) {
        if (req.severity() != null && (req.severity() < 0 || req.severity() > 10)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "severity must be between 0 and 10"));
        }
        return symptomService.update(id, toEntity(req))
                .map(SymptomLogResponse::from)
                .map(r -> ResponseEntity.ok((Object) r))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Deletes a symptom log entry.
     *
     * @param id the entry to delete
     * @return 204 No Content on success, 404 if not found
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return symptomService.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private SymptomLog toEntity(SymptomLogRequest req) {
        SymptomLog log = new SymptomLog();
        log.setLoggedAt(req.loggedAt());
        log.setSeverity(req.severity());
        log.setNotes(req.notes());
        log.setLocationId(req.locationId());
        log.setCityName(req.cityName());
        log.setMedicationsUsed(req.medicationsUsed());
        return log;
    }
}
