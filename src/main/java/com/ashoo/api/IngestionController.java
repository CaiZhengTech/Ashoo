package com.ashoo.api;

import com.ashoo.api.dto.SnapshotResponse;
import com.ashoo.ingestion.IngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Manual triggers for the ingestion pipeline.
 *
 * These endpoints let you trigger ingestion without waiting for the hourly
 * scheduler — essential during development and for the seed-history backfill.
 * In production, the scheduler handles everything automatically.
 */
@RestController
@RequestMapping("/api/v1/ingestion")
public class IngestionController {

    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * Manually triggers one ingestion cycle for the default location.
     *
     * @return the ingested snapshot, or 502 if the API call failed
     */
    @PostMapping("/trigger")
    public ResponseEntity<SnapshotResponse> trigger() {
        return ingestionService.ingestDefaultLocation()
                .map(SnapshotResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(502).build());
    }

    /**
     * Backfills 90 days of historical environmental data for the default location.
     *
     * This is a long-running operation (~2,160 rows). Runs synchronously on a
     * virtual thread so it doesn't block other requests.
     *
     * @return the number of rows inserted
     */
    @PostMapping("/seed-history")
    public ResponseEntity<Map<String, Object>> seedHistory() {
        int count = ingestionService.seedHistory();
        if (count == 0) {
            return ResponseEntity.status(502).body(Map.of(
                    "status", "failed",
                    "message", "No historical data returned from Open-Meteo"));
        }
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "rowsInserted", count));
    }
}
