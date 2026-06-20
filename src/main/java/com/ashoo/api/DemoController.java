package com.ashoo.api;

import com.ashoo.common.DemoUsers;
import com.ashoo.correlation.CorrelationService;
import com.ashoo.correlation.RiskScoringService;
import com.ashoo.ingestion.SeedDemoService;
import com.ashoo.ingestion.openmeteo.OpenMeteoClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/demo")
public class DemoController {

    private static final int TREND_DAYS = 180;

    private final SeedDemoService seedDemoService;
    private final CorrelationService correlationService;
    private final RiskScoringService riskScoringService;
    private final OpenMeteoClient openMeteoClient;

    public DemoController(SeedDemoService seedDemoService,
                         CorrelationService correlationService,
                         RiskScoringService riskScoringService,
                         OpenMeteoClient openMeteoClient) {
        this.seedDemoService = seedDemoService;
        this.correlationService = correlationService;
        this.riskScoringService = riskScoringService;
        this.openMeteoClient = openMeteoClient;
    }

    @GetMapping("/profiles")
    public List<Map<String, String>> listProfiles() {
        return List.of(
                Map.of("persona", "alex",
                        "name", "Alex",
                        "sensitivity", "Low",
                        "location", "London, United Kingdom",
                        "description", "Only poor air quality (high PM2.5) bothers Alex, and only mildly. Rare symptom days, low confidence so far."),
                Map.of("persona", "jordan",
                        "name", "Jordan",
                        "sensitivity", "Moderate",
                        "location", "Berlin, Germany",
                        "description", "A classic pollen sufferer. Symptoms track grass and birch pollen with a clear seasonal pattern."),
                Map.of("persona", "morgan",
                        "name", "Morgan",
                        "sensitivity", "High",
                        "location", "Paris, France",
                        "description", "Reacts to many things at once (pollen, PM2.5, humidity, pressure swings). Frequent, often severe days.")
        );
    }

    /**
     * Seeds all personas AND fully prepares them for viewing: each persona (and the
     * default user) gets its correlation model computed and a daily risk trend backfilled.
     *
     * Doing the full preparation here means a single "Seed" action leaves every persona
     * immediately explorable from the dashboard switcher — risk score, briefing, factor
     * breakdown, Insights, and trend chart all populated — instead of requiring a separate
     * recompute per persona.
     *
     * @return per-persona row counts plus a confirmation the models were computed
     */
    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> seedAllPersonas() {
        Map<String, Integer> counts = seedDemoService.seedAllPersonas();

        // Prepare the default user and every persona so the switcher works out of the box.
        prepare(DemoUsers.DEFAULT_USER);
        for (String persona : DemoUsers.PERSONAS) {
            prepare("demo-" + persona);
        }

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "note", "All rows marked data_origin='SEEDED_SYNTHETIC'; models computed for all personas",
                "symptomRowsInserted", counts
        ));
    }

    /**
     * Changes the "You" user's location: geocodes the city, re-seeds 90 days of
     * environmental history and synthetic symptoms for the new location, then
     * recomputes the model so the dashboard immediately reflects the new city.
     *
     * @param city the city name to geocode (e.g. "Boston, MA", "London")
     * @return the resolved location and confirmation, or 400 if geocoding failed
     */
    @PostMapping("/set-user-location")
    public ResponseEntity<Map<String, Object>> setUserLocation(@RequestParam String city) {
        Optional<OpenMeteoClient.GeocodingResult> geo = openMeteoClient.geocode(city);
        if (geo.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Could not find location: " + city));
        }
        OpenMeteoClient.GeocodingResult result = geo.get();
        seedDemoService.reseedDefaultUser(result.latitude(), result.longitude(), result.displayName());
        prepare(DemoUsers.DEFAULT_USER);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "city", result.displayName(),
                "lat", result.latitude(),
                "lon", result.longitude()));
    }

    /**
     * Computes a user's correlation model and backfills their risk trend.
     *
     * @param userId the persona or default user to prepare
     */
    private void prepare(String userId) {
        String envUserId = DemoUsers.envFor(userId);
        correlationService.computeAndStore(userId, envUserId);
        riskScoringService.backfillHistory(userId, envUserId, TREND_DAYS);
        riskScoringService.scoreAndPersistFresh(userId, envUserId);
    }
}
