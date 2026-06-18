package com.ashoo.ingestion.openmeteo;

import com.ashoo.storage.entity.EnvironmentalSnapshot;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP client for all Open-Meteo APIs (air quality, weather, geocoding, historical).
 *
 * Open-Meteo is free, keyless, and CC BY 4.0 licensed — the ideal data source for V1.
 * This client uses Spring's RestClient (synchronous, virtual-thread-friendly) rather
 * than WebClient (reactive) because our workload is simple sequential polling, not
 * high-concurrency streaming. With virtual threads enabled, the synchronous calls
 * don't block OS threads.
 */
@Service
public class OpenMeteoClient {

    private static final Logger log = LoggerFactory.getLogger(OpenMeteoClient.class);

    private static final String AQ_CURRENT_PARAMS =
            "pm10,pm2_5,ozone,nitrogen_dioxide,sulphur_dioxide,carbon_monoxide,"
            + "alder_pollen,birch_pollen,grass_pollen,mugwort_pollen,olive_pollen,ragweed_pollen";

    private static final String WEATHER_CURRENT_PARAMS =
            "temperature_2m,relative_humidity_2m,surface_pressure,wind_speed_10m,wind_gusts_10m";

    private static final String AQ_HOURLY_PARAMS =
            "pm10,pm2_5,ozone,nitrogen_dioxide,sulphur_dioxide,carbon_monoxide,"
            + "alder_pollen,birch_pollen,grass_pollen,mugwort_pollen,olive_pollen,ragweed_pollen";

    private static final String WEATHER_HOURLY_PARAMS =
            "temperature_2m,relative_humidity_2m,surface_pressure,wind_speed_10m,wind_gusts_10m";

    private final RestClient airQualityClient;
    private final RestClient weatherClient;
    private final RestClient weatherArchiveClient;
    private final RestClient geocodingClient;

    /**
     * Constructs clients for each Open-Meteo API endpoint.
     *
     * Uses {@code builder.clone()} to create independent RestClient instances
     * that don't share interceptors or default headers. Each points at a
     * different Open-Meteo subdomain.
     *
     * @param builder the auto-configured RestClient.Builder from Spring Boot
     */
    public OpenMeteoClient(RestClient.Builder builder) {
        this.airQualityClient = builder.clone()
                .baseUrl("https://air-quality-api.open-meteo.com").build();
        this.weatherClient = builder.clone()
                .baseUrl("https://api.open-meteo.com").build();
        this.weatherArchiveClient = builder.clone()
                .baseUrl("https://archive-api.open-meteo.com").build();
        this.geocodingClient = builder.clone()
                .baseUrl("https://geocoding-api.open-meteo.com").build();
    }

    /**
     * Fetches current air quality + pollen + weather for a coordinate pair
     * and merges them into a single EnvironmentalSnapshot.
     *
     * Makes two HTTP calls (AQ and weather) because Open-Meteo serves them
     * from different endpoints. Pollen data is bundled with air quality.
     * European locations get pollen; non-European locations get nulls for
     * pollen fields (the CAMS model is Europe-only).
     *
     * @param lat      latitude
     * @param lon      longitude
     * @param cityName display name for the location
     * @return a snapshot with raw values populated, or empty on API failure
     */
    public Optional<EnvironmentalSnapshot> fetchCurrent(double lat, double lon, String cityName) {
        try {
            AirQualityResponse aq = airQualityClient.get()
                    .uri("/v1/air-quality?latitude={lat}&longitude={lon}&current={params}",
                            lat, lon, AQ_CURRENT_PARAMS)
                    .retrieve()
                    .body(AirQualityResponse.class);

            WeatherResponse weather = weatherClient.get()
                    .uri("/v1/forecast?latitude={lat}&longitude={lon}&current={params}",
                            lat, lon, WEATHER_CURRENT_PARAMS)
                    .retrieve()
                    .body(WeatherResponse.class);

            if (aq == null || aq.current() == null || weather == null || weather.current() == null) {
                log.warn("Null response from Open-Meteo for ({}, {})", lat, lon);
                return Optional.empty();
            }

            return Optional.of(mergeCurrentResponse(aq, weather, cityName));

        } catch (RestClientException e) {
            log.error("Open-Meteo API call failed for ({}, {}): {}", lat, lon, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Geocodes a city name to coordinates using Open-Meteo's geocoding API.
     *
     * Open-Meteo's geocoder matches on the city name alone — it returns no results for
     * a combined "City, ST" string, which is exactly what users naturally type (and what
     * our placeholder suggests). So we split on the comma, search by the city part, then
     * disambiguate among candidates by matching the region hint against each result's
     * {@code admin1} (state/province). This both fixes the outright failure and picks the
     * RIGHT "Sharon" — there are dozens, and without the state we'd grab an arbitrary one.
     *
     * @param query the user's input, e.g. "Amsterdam", "Sharon, MA", or "Paris, France"
     * @return lat/lon and resolved name, or empty if not found
     */
    public Optional<GeocodingResult> geocode(String query) {
        if (query == null || query.isBlank()) return Optional.empty();

        String[] parts = query.split(",", 2);
        String cityPart = parts[0].trim();
        String regionHint = parts.length > 1 ? parts[1].trim() : null;

        try {
            GeocodingResponse response = geocodingClient.get()
                    .uri("/v1/search?name={name}&count=10&language=en", cityPart)
                    .retrieve()
                    .body(GeocodingResponse.class);

            if (response == null || response.results() == null || response.results().isEmpty()) {
                return Optional.empty();
            }

            var match = pickBestMatch(response.results(), regionHint);

            StringBuilder display = new StringBuilder(match.name());
            if (match.admin1() != null && !match.admin1().isBlank()) {
                display.append(", ").append(match.admin1());
            }
            if (match.country() != null) {
                display.append(", ").append(match.country());
            }
            return Optional.of(new GeocodingResult(match.latitude(), match.longitude(), display.toString()));

        } catch (RestClientException e) {
            log.error("Geocoding failed for '{}': {}", query, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Returns up to {@code limit} place candidates for an autocomplete dropdown.
     *
     * The frontend search box calls this as the user types so they can pick the exact
     * place (there are many "Sharon"s) rather than trusting a single best-guess match.
     * Each candidate carries a display name with state/region and country for disambiguation.
     *
     * @param query the partial place name the user has typed
     * @param limit max candidates to return
     * @return matching places, most relevant first (empty on no match or error)
     */
    public List<GeocodingResult> geocodeSuggestions(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        String cityPart = query.split(",", 2)[0].trim();
        try {
            GeocodingResponse response = geocodingClient.get()
                    .uri("/v1/search?name={name}&count={count}&language=en", cityPart, limit)
                    .retrieve()
                    .body(GeocodingResponse.class);
            if (response == null || response.results() == null) return List.of();
            return response.results().stream().map(this::toResult).toList();
        } catch (RestClientException e) {
            log.error("Geocoding suggestions failed for '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    /** Builds a display-friendly result (name, region, country) from a raw candidate. */
    private GeocodingResult toResult(GeocodingResponse.GeoResult r) {
        StringBuilder display = new StringBuilder(r.name());
        if (r.admin1() != null && !r.admin1().isBlank()) display.append(", ").append(r.admin1());
        if (r.country() != null) display.append(", ").append(r.country());
        return new GeocodingResult(r.latitude(), r.longitude(), display.toString());
    }

    /**
     * Chooses the candidate that best matches an optional region hint.
     *
     * The hint is matched against {@code admin1} both directly (e.g. "Massachusetts")
     * and via a US state-abbreviation expansion (e.g. "MA"). When no hint is given or
     * nothing matches, we keep Open-Meteo's own top result, which is population-ranked.
     */
    private GeocodingResponse.GeoResult pickBestMatch(
            List<GeocodingResponse.GeoResult> results, String regionHint) {
        if (regionHint == null || regionHint.isBlank()) {
            return results.getFirst();
        }
        String hint = regionHint.trim();
        String expanded = US_STATES.getOrDefault(hint.toUpperCase(), hint);
        for (var r : results) {
            String admin1 = r.admin1();
            if (admin1 == null) continue;
            if (admin1.equalsIgnoreCase(hint) || admin1.equalsIgnoreCase(expanded)) {
                return r;
            }
        }
        return results.getFirst();
    }

    /** Minimal US state abbreviation → full name map, so "Sharon, MA" resolves correctly. */
    private static final Map<String, String> US_STATES = Map.ofEntries(
            Map.entry("AL", "Alabama"), Map.entry("AK", "Alaska"), Map.entry("AZ", "Arizona"),
            Map.entry("AR", "Arkansas"), Map.entry("CA", "California"), Map.entry("CO", "Colorado"),
            Map.entry("CT", "Connecticut"), Map.entry("DE", "Delaware"), Map.entry("FL", "Florida"),
            Map.entry("GA", "Georgia"), Map.entry("HI", "Hawaii"), Map.entry("ID", "Idaho"),
            Map.entry("IL", "Illinois"), Map.entry("IN", "Indiana"), Map.entry("IA", "Iowa"),
            Map.entry("KS", "Kansas"), Map.entry("KY", "Kentucky"), Map.entry("LA", "Louisiana"),
            Map.entry("ME", "Maine"), Map.entry("MD", "Maryland"), Map.entry("MA", "Massachusetts"),
            Map.entry("MI", "Michigan"), Map.entry("MN", "Minnesota"), Map.entry("MS", "Mississippi"),
            Map.entry("MO", "Missouri"), Map.entry("MT", "Montana"), Map.entry("NE", "Nebraska"),
            Map.entry("NV", "Nevada"), Map.entry("NH", "New Hampshire"), Map.entry("NJ", "New Jersey"),
            Map.entry("NM", "New Mexico"), Map.entry("NY", "New York"), Map.entry("NC", "North Carolina"),
            Map.entry("ND", "North Dakota"), Map.entry("OH", "Ohio"), Map.entry("OK", "Oklahoma"),
            Map.entry("OR", "Oregon"), Map.entry("PA", "Pennsylvania"), Map.entry("RI", "Rhode Island"),
            Map.entry("SC", "South Carolina"), Map.entry("SD", "South Dakota"), Map.entry("TN", "Tennessee"),
            Map.entry("TX", "Texas"), Map.entry("UT", "Utah"), Map.entry("VT", "Vermont"),
            Map.entry("VA", "Virginia"), Map.entry("WA", "Washington"), Map.entry("WV", "West Virginia"),
            Map.entry("WI", "Wisconsin"), Map.entry("WY", "Wyoming"), Map.entry("DC", "Washington, D.C.")
    );

    /**
     * Fetches historical hourly data for a coordinate pair over a date range.
     *
     * Used by the seed-history endpoint to backfill environmental data.
     * Open-Meteo allows up to 3 months per request, so a 90-day backfill
     * fits in two calls (one AQ, one weather).
     *
     * @param lat       latitude
     * @param lon       longitude
     * @param cityName  display name for the location
     * @param startDate first day to fetch (inclusive)
     * @param endDate   last day to fetch (inclusive)
     * @return list of hourly snapshots, or empty list on failure
     */
    public List<EnvironmentalSnapshot> fetchHistorical(double lat, double lon, String cityName,
                                                        LocalDate startDate, LocalDate endDate) {
        try {
            String start = startDate.toString();
            String end = endDate.toString();

            AirQualityHourlyResponse aq = airQualityClient.get()
                    .uri("/v1/air-quality?latitude={lat}&longitude={lon}&hourly={params}&start_date={start}&end_date={end}",
                            lat, lon, AQ_HOURLY_PARAMS, start, end)
                    .retrieve()
                    .body(AirQualityHourlyResponse.class);

            WeatherHourlyResponse weather = weatherArchiveClient.get()
                    .uri("/v1/archive?latitude={lat}&longitude={lon}&hourly={params}&start_date={start}&end_date={end}",
                            lat, lon, WEATHER_HOURLY_PARAMS, start, end)
                    .retrieve()
                    .body(WeatherHourlyResponse.class);

            if (aq == null || aq.hourly() == null || weather == null || weather.hourly() == null) {
                log.warn("Null historical response for ({}, {})", lat, lon);
                return List.of();
            }

            return mergeHourlyResponses(aq, weather, cityName);

        } catch (RestClientException e) {
            log.error("Historical fetch failed for ({}, {}): {}", lat, lon, e.getMessage());
            return List.of();
        }
    }

    // --- Response DTOs ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AirQualityResponse(double latitude, double longitude, AqCurrent current) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record AqCurrent(
                String time,
                @JsonProperty("pm2_5") Double pm25,
                @JsonProperty("pm10") Double pm10,
                @JsonProperty("ozone") Double ozone,
                @JsonProperty("nitrogen_dioxide") Double nitrogenDioxide,
                @JsonProperty("sulphur_dioxide") Double sulphurDioxide,
                @JsonProperty("carbon_monoxide") Double carbonMonoxide,
                @JsonProperty("alder_pollen") Double alderPollen,
                @JsonProperty("birch_pollen") Double birchPollen,
                @JsonProperty("grass_pollen") Double grassPollen,
                @JsonProperty("mugwort_pollen") Double mugwortPollen,
                @JsonProperty("olive_pollen") Double olivePollen,
                @JsonProperty("ragweed_pollen") Double ragweedPollen
        ) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WeatherResponse(double latitude, double longitude, WeatherCurrent current) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record WeatherCurrent(
                String time,
                @JsonProperty("temperature_2m") Double temperature,
                @JsonProperty("relative_humidity_2m") Double humidity,
                @JsonProperty("surface_pressure") Double pressure,
                @JsonProperty("wind_speed_10m") Double windSpeed,
                @JsonProperty("wind_gusts_10m") Double windGusts
        ) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AirQualityHourlyResponse(double latitude, double longitude, AqHourly hourly) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record AqHourly(
                List<String> time,
                @JsonProperty("pm2_5") List<Double> pm25,
                @JsonProperty("pm10") List<Double> pm10,
                @JsonProperty("ozone") List<Double> ozone,
                @JsonProperty("nitrogen_dioxide") List<Double> nitrogenDioxide,
                @JsonProperty("sulphur_dioxide") List<Double> sulphurDioxide,
                @JsonProperty("carbon_monoxide") List<Double> carbonMonoxide,
                @JsonProperty("alder_pollen") List<Double> alderPollen,
                @JsonProperty("birch_pollen") List<Double> birchPollen,
                @JsonProperty("grass_pollen") List<Double> grassPollen,
                @JsonProperty("mugwort_pollen") List<Double> mugwortPollen,
                @JsonProperty("olive_pollen") List<Double> olivePollen,
                @JsonProperty("ragweed_pollen") List<Double> ragweedPollen
        ) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WeatherHourlyResponse(double latitude, double longitude, WeatherHourly hourly) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record WeatherHourly(
                List<String> time,
                @JsonProperty("temperature_2m") List<Double> temperature,
                @JsonProperty("relative_humidity_2m") List<Double> humidity,
                @JsonProperty("surface_pressure") List<Double> pressure,
                @JsonProperty("wind_speed_10m") List<Double> windSpeed,
                @JsonProperty("wind_gusts_10m") List<Double> windGusts
        ) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GeocodingResponse(List<GeoResult> results) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record GeoResult(String name, double latitude, double longitude,
                         String country, String admin1) {}
    }

    /** Resolved geocoding result with display-friendly name. */
    public record GeocodingResult(double latitude, double longitude, String displayName) {}

    // --- Merge helpers ---

    private EnvironmentalSnapshot mergeCurrentResponse(AirQualityResponse aq,
                                                        WeatherResponse weather,
                                                        String cityName) {
        var a = aq.current();
        var w = weather.current();
        Instant time = parseTime(a.time());

        return EnvironmentalSnapshot.builder()
                .recordedAt(time)
                .latitude(aq.latitude())
                .longitude(aq.longitude())
                .cityName(cityName)
                .pm25(a.pm25()).pm10(a.pm10()).o3(a.ozone())
                .no2(a.nitrogenDioxide()).so2(a.sulphurDioxide()).co(a.carbonMonoxide())
                .pollenAlder(a.alderPollen()).pollenBirch(a.birchPollen())
                .pollenGrass(a.grassPollen()).pollenMugwort(a.mugwortPollen())
                .pollenOlive(a.olivePollen()).pollenRagweed(a.ragweedPollen())
                .temperatureC(w.temperature()).humidityPct(w.humidity())
                .pressureMslHpa(w.pressure())
                .windSpeedMs(w.windSpeed()).windGustsMs(w.windGusts())
                .dataSource("OPEN_METEO")
                .build();
    }

    private List<EnvironmentalSnapshot> mergeHourlyResponses(AirQualityHourlyResponse aq,
                                                              WeatherHourlyResponse weather,
                                                              String cityName) {
        var a = aq.hourly();
        var w = weather.hourly();
        int aqLen = a.time().size();
        int wLen = w.time().size();

        // Build a lookup from weather timestamps for merging
        java.util.Map<String, Integer> weatherIndex = new java.util.HashMap<>();
        for (int i = 0; i < wLen; i++) {
            weatherIndex.put(w.time().get(i), i);
        }

        List<EnvironmentalSnapshot> results = new ArrayList<>(aqLen);
        for (int i = 0; i < aqLen; i++) {
            String timeStr = a.time().get(i);
            Instant time = parseTime(timeStr);

            var builder = EnvironmentalSnapshot.builder()
                    .recordedAt(time)
                    .latitude(aq.latitude()).longitude(aq.longitude())
                    .cityName(cityName)
                    .pm25(safeGet(a.pm25(), i)).pm10(safeGet(a.pm10(), i))
                    .o3(safeGet(a.ozone(), i))
                    .no2(safeGet(a.nitrogenDioxide(), i))
                    .so2(safeGet(a.sulphurDioxide(), i))
                    .co(safeGet(a.carbonMonoxide(), i))
                    .pollenAlder(safeGet(a.alderPollen(), i))
                    .pollenBirch(safeGet(a.birchPollen(), i))
                    .pollenGrass(safeGet(a.grassPollen(), i))
                    .pollenMugwort(safeGet(a.mugwortPollen(), i))
                    .pollenOlive(safeGet(a.olivePollen(), i))
                    .pollenRagweed(safeGet(a.ragweedPollen(), i))
                    .dataSource("OPEN_METEO");

            Integer wIdx = weatherIndex.get(timeStr);
            if (wIdx != null) {
                builder.temperatureC(safeGet(w.temperature(), wIdx))
                        .humidityPct(safeGet(w.humidity(), wIdx))
                        .pressureMslHpa(safeGet(w.pressure(), wIdx))
                        .windSpeedMs(safeGet(w.windSpeed(), wIdx))
                        .windGustsMs(safeGet(w.windGusts(), wIdx));
            }

            results.add(builder.build());
        }
        return results;
    }

    private static Instant parseTime(String isoLocal) {
        return LocalDateTime.parse(isoLocal, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .toInstant(ZoneOffset.UTC);
    }

    private static Double safeGet(List<Double> list, int index) {
        if (list == null || index >= list.size()) return null;
        return list.get(index);
    }
}
