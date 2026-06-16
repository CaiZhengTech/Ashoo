package com.ashoo.api;

import com.ashoo.ingestion.SeedDemoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Endpoints for the recruiter demo mode.
 *
 * Demo data is clearly labeled throughout — all seeded rows have
 * {@code data_origin = 'SEEDED_SYNTHETIC'} in the database, and every
 * response from these endpoints identifies itself as demo data.
 */
@RestController
@RequestMapping("/api/v1/demo")
public class DemoController {

    private final SeedDemoService seedDemoService;

    public DemoController(SeedDemoService seedDemoService) {
        this.seedDemoService = seedDemoService;
    }

    /**
     * Lists the three demo personas with their sensitivity descriptions.
     *
     * @return list of persona profiles
     */
    @GetMapping("/profiles")
    public List<Map<String, String>> listProfiles() {
        return List.of(
                Map.of("persona", "alex",
                        "name", "Alex",
                        "sensitivity", "Low",
                        "description", "Symptoms only under strict multi-trigger conditions (PM2.5 > 25 AND humidity > 70%). Severity capped at 4."),
                Map.of("persona", "jordan",
                        "name", "Jordan",
                        "sensitivity", "Moderate",
                        "description", "Triggered by PM2.5 > 18 OR grass pollen > 30. Clear seasonal pattern. Severity 3–7."),
                Map.of("persona", "morgan",
                        "name", "Morgan",
                        "sensitivity", "High",
                        "description", "Triggered by PM2.5 > 12 OR pollen > 15 OR humidity > 60%. Frequent severe episodes. Shows mismatch days.")
        );
    }

    /**
     * Seeds all three demo personas with 90 days of real + synthetic data.
     *
     * This is idempotent in terms of structure but will insert duplicate rows
     * if called more than once. In V1 (single user, no auth) this is acceptable —
     * V2 multi-user will add per-user deduplication.
     *
     * @return row counts per persona
     */
    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> seedAllPersonas() {
        Map<String, Integer> counts = seedDemoService.seedAllPersonas();
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "note", "All rows marked data_origin='SEEDED_SYNTHETIC'",
                "symptomRowsInserted", counts
        ));
    }
}
