package com.ashoo.api;

import com.ashoo.api.dto.SavedLocationRequest;
import com.ashoo.api.dto.SavedLocationResponse;
import com.ashoo.location.LocationService;
import com.ashoo.storage.entity.RecentSearch;
import com.ashoo.storage.entity.SavedLocation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * CRUD endpoints for saved locations and recent searches.
 *
 * Saved locations are pre-registered by the user and pre-fetched hourly.
 * Recent searches are auto-saved by ConditionsController when the user
 * looks up a city ad-hoc.
 */
@RestController
@RequestMapping("/api/v1/locations")
public class LocationController {

    private final LocationService locationService;

    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    /**
     * Adds a new saved location.
     *
     * lat/lon are optional — if omitted, cityName is geocoded automatically.
     * Accepts "City, State" or "City, Country" formats (e.g. "Sharon, MA",
     * "Amsterdam, Netherlands"). Returns 400 if the name can't be resolved.
     *
     * @param req the location details
     * @return the created location with 201 Created, or 400 if geocoding fails
     */
    @PostMapping
    public ResponseEntity<?> addLocation(@RequestBody SavedLocationRequest req) {
        try {
            SavedLocation location = toEntity(req);
            SavedLocation saved = locationService.addLocation(location);
            return ResponseEntity
                    .created(URI.create("/api/v1/locations/" + saved.getId()))
                    .body(SavedLocationResponse.from(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Lists all active saved locations for the default user.
     *
     * @return list of active locations
     */
    @GetMapping
    public List<SavedLocationResponse> listLocations() {
        return locationService.listLocations().stream()
                .map(SavedLocationResponse::from)
                .toList();
    }

    /**
     * Updates an existing saved location.
     *
     * lat/lon are optional — if omitted but a new cityName is provided,
     * the coordinates are geocoded automatically.
     *
     * @param id  the location id
     * @param req updated field values
     * @return the updated location, or 404 if not found, 400 if geocoding fails
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateLocation(@PathVariable Long id,
                                             @RequestBody SavedLocationRequest req) {
        try {
            return locationService.updateLocation(id, toEntity(req))
                    .map(SavedLocationResponse::from)
                    .map(r -> ResponseEntity.ok((Object) r))
                    .orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Soft-deletes a saved location (marks it inactive).
     *
     * @param id the location id
     * @return 204 No Content on success, 404 if not found
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> removeLocation(@PathVariable Long id) {
        return locationService.removeLocation(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /**
     * Returns the last 10 ad-hoc location lookups.
     *
     * @return recent searches, newest first
     */
    @GetMapping("/recent-searches")
    public List<Map<String, Object>> getRecentSearches() {
        return locationService.listRecentSearches().stream()
                .map(this::toMap)
                .toList();
    }

    /**
     * Type-ahead place suggestions for the search box.
     *
     * @param q     the partial place name being typed
     * @param limit max suggestions (defaults to 6)
     * @return candidate places with display name and coordinates
     */
    @GetMapping("/suggest")
    public List<Map<String, Object>> suggest(@RequestParam String q,
                                             @RequestParam(defaultValue = "6") int limit) {
        return locationService.suggest(q, limit).stream()
                .map(g -> Map.<String, Object>of(
                        "cityName", g.displayName(),
                        "latitude", g.latitude(),
                        "longitude", g.longitude()))
                .toList();
    }

    private Map<String, Object> toMap(RecentSearch s) {
        return Map.of(
                "id", s.getId(),
                "cityName", s.getCityName(),
                "latitude", s.getLatitude() != null ? s.getLatitude() : 0.0,
                "longitude", s.getLongitude() != null ? s.getLongitude() : 0.0,
                "searchedAt", s.getSearchedAt()
        );
    }

    private SavedLocation toEntity(SavedLocationRequest req) {
        SavedLocation loc = new SavedLocation();
        loc.setLabel(req.label());
        loc.setCityName(req.cityName());
        loc.setCounty(req.county());
        loc.setCountry(req.country());
        loc.setLatitude(req.latitude());
        loc.setLongitude(req.longitude());
        loc.setIsPrimary(req.isPrimary());
        return loc;
    }
}
