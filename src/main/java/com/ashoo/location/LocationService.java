package com.ashoo.location;

import com.ashoo.ingestion.openmeteo.OpenMeteoClient;
import com.ashoo.storage.entity.RecentSearch;
import com.ashoo.storage.entity.SavedLocation;
import com.ashoo.storage.repository.RecentSearchRepository;
import com.ashoo.storage.repository.SavedLocationRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Business logic for saved locations and recent searches.
 *
 * Keeps the controller thin by handling the rules that belong to the domain:
 * primary-flag management (only one primary per user), the rolling-10 pruning
 * for recent searches, and geocoding when only a city name is provided.
 */
@Service
public class LocationService {

    private static final String DEFAULT_USER = "ashoo-user";

    private final SavedLocationRepository savedLocationRepo;
    private final RecentSearchRepository recentSearchRepo;
    private final OpenMeteoClient openMeteoClient;

    public LocationService(SavedLocationRepository savedLocationRepo,
                            RecentSearchRepository recentSearchRepo,
                            OpenMeteoClient openMeteoClient) {
        this.savedLocationRepo = savedLocationRepo;
        this.recentSearchRepo = recentSearchRepo;
        this.openMeteoClient = openMeteoClient;
    }

    /**
     * Saves a new location for the default user.
     *
     * If lat/lon are omitted, the city name is geocoded via Open-Meteo so the
     * caller only needs to supply a human-readable name like "Sharon, MA".
     * The resolved display name is also written back to cityName so the stored
     * label is the canonical form returned by the geocoder (e.g. "Sharon, United States").
     * Throws if the city can't be resolved and no coordinates were provided.
     *
     * If the new location is marked primary, clears the primary flag on all other
     * locations first — only one primary is allowed per user at any time.
     *
     * @param location the location to save (userId and createdAt are set here)
     * @return the persisted location with its generated id
     * @throws IllegalArgumentException if cityName can't be geocoded and lat/lon are absent
     */
    public SavedLocation addLocation(SavedLocation location) {
        location.setUserId(DEFAULT_USER);
        location.setCreatedAt(Instant.now());
        if (location.getIsActive() == null)  location.setIsActive(true);
        if (location.getIsPrimary() == null) location.setIsPrimary(false);
        if (location.getCountry() == null)   location.setCountry("US");

        if (location.getLatitude() == null || location.getLongitude() == null) {
            String query = location.getCityName();
            if (query == null || query.isBlank()) {
                throw new IllegalArgumentException(
                        "Provide either a cityName (to auto-geocode) or explicit latitude and longitude.");
            }
            OpenMeteoClient.GeocodingResult geo = openMeteoClient.geocode(query)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Could not geocode \"" + query + "\" — check the city name and try again."));
            location.setLatitude(geo.latitude());
            location.setLongitude(geo.longitude());
            location.setCityName(geo.displayName());
        }

        if (Boolean.TRUE.equals(location.getIsPrimary())) {
            savedLocationRepo.clearPrimaryForUser(DEFAULT_USER);
        }
        return savedLocationRepo.save(location);
    }

    /**
     * Returns all active saved locations for the default user.
     *
     * @return list of active locations, newest first
     */
    public List<SavedLocation> listLocations() {
        return savedLocationRepo.findActiveByUserId(DEFAULT_USER);
    }

    /**
     * Returns place suggestions for a type-ahead search box.
     *
     * Delegates to the geocoder's multi-result lookup so the user can disambiguate
     * between similarly named places before committing to one.
     *
     * @param query the partial place name being typed
     * @param limit max suggestions to return
     * @return candidate places (empty if none/blank/too short)
     */
    public List<OpenMeteoClient.GeocodingResult> suggest(String query, int limit) {
        if (query == null || query.trim().length() < 2) return List.of();
        return openMeteoClient.geocodeSuggestions(query, limit);
    }

    /**
     * Updates a saved location and returns the result.
     *
     * If a new cityName is provided without coordinates, the city is geocoded
     * automatically so the caller never has to look up lat/lon manually.
     * Explicit lat/lon in the request always take precedence over geocoding.
     *
     * Returns empty if the location does not belong to the default user.
     *
     * @param id      the location id to update
     * @param updated the updated field values
     * @return the saved location, or empty if not found
     * @throws IllegalArgumentException if a new cityName can't be geocoded
     */
    public Optional<SavedLocation> updateLocation(Long id, SavedLocation updated) {
        return savedLocationRepo.findById(id)
                .filter(existing -> DEFAULT_USER.equals(existing.getUserId()))
                .map(existing -> {
                    // If caller supplied a new city but no new coordinates, geocode it
                    if (updated.getCityName() != null
                            && updated.getLatitude() == null
                            && updated.getLongitude() == null) {
                        OpenMeteoClient.GeocodingResult geo = openMeteoClient
                                .geocode(updated.getCityName())
                                .orElseThrow(() -> new IllegalArgumentException(
                                        "Could not geocode \"" + updated.getCityName() + "\" — check the city name and try again."));
                        existing.setCityName(geo.displayName());
                        existing.setLatitude(geo.latitude());
                        existing.setLongitude(geo.longitude());
                    } else {
                        if (updated.getCityName() != null)  existing.setCityName(updated.getCityName());
                        if (updated.getLatitude() != null)  existing.setLatitude(updated.getLatitude());
                        if (updated.getLongitude() != null) existing.setLongitude(updated.getLongitude());
                    }

                    if (updated.getLabel() != null)   existing.setLabel(updated.getLabel());
                    if (updated.getCounty() != null)  existing.setCounty(updated.getCounty());
                    if (updated.getCountry() != null) existing.setCountry(updated.getCountry());
                    if (updated.getIsPrimary() != null && Boolean.TRUE.equals(updated.getIsPrimary())) {
                        savedLocationRepo.clearPrimaryForUser(DEFAULT_USER);
                        existing.setIsPrimary(true);
                    }
                    return savedLocationRepo.save(existing);
                });
    }

    /**
     * Soft-deletes a location by marking it inactive.
     *
     * We never hard-delete saved locations because symptom_log entries may
     * reference their id. Setting isActive = false hides them from the UI
     * while preserving referential integrity.
     *
     * @param id the location id to deactivate
     * @return true if found and deactivated, false if not found
     */
    public boolean removeLocation(Long id) {
        return savedLocationRepo.findById(id)
                .filter(l -> DEFAULT_USER.equals(l.getUserId()))
                .map(l -> {
                    l.setIsActive(false);
                    savedLocationRepo.save(l);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Records an ad-hoc city search and maintains the rolling-10 limit.
     *
     * Called whenever the user queries /conditions?city= so they can
     * quickly re-access recent lookups without re-typing.
     *
     * @param cityName  the resolved city name to record
     * @param latitude  resolved latitude
     * @param longitude resolved longitude
     */
    public void recordRecentSearch(String cityName, Double latitude, Double longitude) {
        RecentSearch search = new RecentSearch();
        search.setUserId(DEFAULT_USER);
        search.setCityName(cityName);
        search.setLatitude(latitude);
        search.setLongitude(longitude);
        search.setSearchedAt(Instant.now());
        recentSearchRepo.save(search);
        recentSearchRepo.pruneToTen(DEFAULT_USER);
    }

    /**
     * Returns the last 10 ad-hoc location searches for the default user.
     *
     * @return recent searches, newest first
     */
    public List<RecentSearch> listRecentSearches() {
        return recentSearchRepo.findRecentByUserId(DEFAULT_USER);
    }
}
