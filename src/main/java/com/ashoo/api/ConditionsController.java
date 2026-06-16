package com.ashoo.api;

import com.ashoo.api.dto.SnapshotResponse;
import com.ashoo.ingestion.IngestionService;
import com.ashoo.ingestion.openmeteo.OpenMeteoClient;
import com.ashoo.location.LocationService;
import com.ashoo.storage.entity.EnvironmentalSnapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * On-demand environmental conditions lookup for any location.
 *
 * Unlike the snapshot endpoints which return stored historical data,
 * this controller fetches live data from Open-Meteo for any coordinates
 * or city name. It is the interactive feature for recruiters/demos —
 * "type any city, see what the air is doing there right now."
 */
@RestController
@RequestMapping("/api/v1/conditions")
public class ConditionsController {

    private final OpenMeteoClient openMeteoClient;
    private final IngestionService ingestionService;
    private final LocationService locationService;

    public ConditionsController(OpenMeteoClient openMeteoClient,
                                 IngestionService ingestionService,
                                 LocationService locationService) {
        this.openMeteoClient = openMeteoClient;
        this.ingestionService = ingestionService;
        this.locationService = locationService;
    }

    /**
     * Fetches live conditions for a latitude/longitude pair.
     *
     * @param lat latitude
     * @param lon longitude
     * @return current environmental conditions, or 502 if API is unreachable
     */
    @GetMapping(params = {"lat", "lon"})
    public ResponseEntity<SnapshotResponse> getByCoordinates(
            @RequestParam double lat,
            @RequestParam double lon) {
        Optional<EnvironmentalSnapshot> snapshot =
                openMeteoClient.fetchCurrent(lat, lon, String.format("%.2f, %.2f", lat, lon));
        return snapshot
                .map(SnapshotResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(502).build());
    }

    /**
     * Fetches live conditions for a city name.
     *
     * Geocodes the city name first via Open-Meteo, fetches conditions for the
     * resolved coordinates, then records this as a recent search so the user
     * can quickly re-access it from the locations panel.
     *
     * @param city the city name to look up (e.g., "Amsterdam")
     * @return current conditions, or 404 if the city can't be geocoded
     */
    @GetMapping(params = "city")
    public ResponseEntity<SnapshotResponse> getByCity(@RequestParam String city) {
        Optional<OpenMeteoClient.GeocodingResult> geo = openMeteoClient.geocode(city);
        if (geo.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        var resolved = geo.get();
        Optional<EnvironmentalSnapshot> snapshot =
                openMeteoClient.fetchCurrent(resolved.latitude(), resolved.longitude(),
                        resolved.displayName());

        if (snapshot.isPresent()) {
            locationService.recordRecentSearch(
                    resolved.displayName(), resolved.latitude(), resolved.longitude());
        }

        return snapshot
                .map(SnapshotResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(502).build());
    }
}
