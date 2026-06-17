package com.ashoo.api;

import com.ashoo.api.dto.MedicationRequest;
import com.ashoo.api.dto.MedicationResponse;
import com.ashoo.reminder.MedicationService;
import com.ashoo.reminder.MedicationType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * CRUD for the user's registered medications, plus usage stats.
 *
 * Every operation is consent-gated in the service layer — calling these without prior
 * consent yields a 403 via {@link com.ashoo.reminder.ConsentRequiredException}. The
 * controller validates the medication type against the fixed enum before delegating.
 */
@RestController
@RequestMapping("/api/v1/medications")
public class MedicationController {

    private static final String DEFAULT_USER = "ashoo-user";

    private final MedicationService medicationService;

    public MedicationController(MedicationService medicationService) {
        this.medicationService = medicationService;
    }

    /**
     * Registers a medication.
     *
     * @param req the medication details (type must be a valid MedicationType)
     * @return the created medication (201), or 400 if the type is unrecognized
     */
    @PostMapping
    public ResponseEntity<?> register(@RequestBody MedicationRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        MedicationType type = MedicationType.fromString(req.type());
        if (type == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "type must be one of INHALER, ANTIHISTAMINE, EPINEPHRINE, "
                            + "NASAL_SPRAY, EYE_DROPS, OTHER"));
        }
        var saved = medicationService.register(DEFAULT_USER, req.name().trim(), type, req.notes());
        return ResponseEntity
                .created(URI.create("/api/v1/medications/" + saved.getId()))
                .body(MedicationResponse.from(saved));
    }

    /**
     * Lists the user's active medications.
     *
     * @return active medications
     */
    @GetMapping
    public List<MedicationResponse> list() {
        return medicationService.list(DEFAULT_USER).stream()
                .map(MedicationResponse::from)
                .toList();
    }

    /**
     * Returns per-medication usage stats (recent vs. longer-term average).
     *
     * @return usage stats for each active medication
     */
    @GetMapping("/usage")
    public List<MedicationService.UsageStat> usage() {
        return medicationService.computeUsageStats(DEFAULT_USER);
    }

    /**
     * Soft-deletes a medication.
     *
     * @param id the medication id
     * @return 204 if removed, 404 if not found
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable Long id) {
        return medicationService.remove(DEFAULT_USER, id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
