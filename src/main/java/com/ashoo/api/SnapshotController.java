package com.ashoo.api;

import com.ashoo.api.dto.SnapshotResponse;
import com.ashoo.storage.repository.EnvironmentalSnapshotRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * REST endpoints for querying stored environmental snapshots.
 *
 * These endpoints return data that has already been ingested and stored.
 * For on-demand live lookups of arbitrary locations, use
 * {@link ConditionsController} instead.
 */
@RestController
@RequestMapping("/api/v1/snapshots")
public class SnapshotController {

    private static final String DEFAULT_USER = "ashoo-user";

    private final EnvironmentalSnapshotRepository snapshotRepo;

    public SnapshotController(EnvironmentalSnapshotRepository snapshotRepo) {
        this.snapshotRepo = snapshotRepo;
    }

    /**
     * Returns the most recent environmental snapshot for the default user.
     *
     * @return the latest snapshot, or 404 if no data has been ingested yet
     */
    @GetMapping("/latest")
    public ResponseEntity<SnapshotResponse> getLatest() {
        return snapshotRepo.findLatest(DEFAULT_USER)
                .map(SnapshotResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Returns snapshots within a time range.
     *
     * @param from inclusive start of the range (ISO-8601)
     * @param to   inclusive end of the range (ISO-8601)
     * @return list of snapshots, newest first
     */
    @GetMapping
    public List<SnapshotResponse> getByDateRange(
            @RequestParam Instant from,
            @RequestParam Instant to) {
        return snapshotRepo.findByDateRange(DEFAULT_USER, from, to)
                .stream()
                .map(SnapshotResponse::from)
                .toList();
    }
}
