package com.ashoo.ingestion;

import com.ashoo.common.AshooProperties;
import com.ashoo.ingestion.openmeteo.OpenMeteoClient;
import com.ashoo.storage.entity.EnvironmentalSnapshot;
import com.ashoo.storage.repository.EnvironmentalSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates the full ingestion pipeline: fetch → compute derived → persist.
 *
 * This service is the single entry point for all data ingestion — both the
 * hourly scheduler and the manual trigger endpoint call the same method.
 * Keeping the orchestration in a service (not the scheduler) makes it
 * independently testable and reusable.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final OpenMeteoClient openMeteoClient;
    private final DerivedSignalCalculator derivedCalc;
    private final EnvironmentalSnapshotRepository snapshotRepo;
    private final AshooProperties props;

    public IngestionService(OpenMeteoClient openMeteoClient,
                             DerivedSignalCalculator derivedCalc,
                             EnvironmentalSnapshotRepository snapshotRepo,
                             AshooProperties props) {
        this.openMeteoClient = openMeteoClient;
        this.derivedCalc = derivedCalc;
        this.snapshotRepo = snapshotRepo;
        this.props = props;
    }

    /**
     * Runs one ingestion cycle for the default location.
     *
     * Fetches current conditions from Open-Meteo, computes derived signals
     * by comparing with the previous reading, and persists the result.
     * Returns the saved snapshot so the caller can confirm the operation.
     *
     * @return the persisted snapshot, or empty if the API call failed
     */
    public Optional<EnvironmentalSnapshot> ingestDefaultLocation() {
        var loc = props.defaultLocation();
        return ingestLocation(loc.latitude(), loc.longitude(), loc.city());
    }

    /**
     * Runs one ingestion cycle for a specific coordinate pair.
     *
     * @param lat      latitude
     * @param lon      longitude
     * @param cityName display name for the location
     * @return the persisted snapshot, or empty on failure
     */
    public Optional<EnvironmentalSnapshot> ingestLocation(double lat, double lon, String cityName) {
        Optional<EnvironmentalSnapshot> fetched = openMeteoClient.fetchCurrent(lat, lon, cityName);
        if (fetched.isEmpty()) {
            log.warn("No data returned for ({}, {})", lat, lon);
            return Optional.empty();
        }

        EnvironmentalSnapshot snapshot = fetched.get();
        snapshot = withUserDefaults(snapshot);

        Optional<EnvironmentalSnapshot> previous = snapshotRepo.findPrevious(
                snapshot.getUserId(), snapshot.getRecordedAt());
        derivedCalc.compute(snapshot, previous);

        snapshotRepo.save(snapshot);
        log.info("Ingested snapshot for {} at {}", cityName, snapshot.getRecordedAt());
        return Optional.of(snapshot);
    }

    /**
     * Backfills historical environmental data for the default location.
     *
     * Fetches 90 days of hourly data from Open-Meteo's archive API,
     * computes derived signals for each row (using sequential comparison),
     * and batch-inserts all rows. This is the foundation the correlation
     * engine needs before it can compute meaningful thresholds.
     *
     * @return number of rows inserted
     */
    public int seedHistory() {
        var loc = props.defaultLocation();
        return seedHistory(loc.latitude(), loc.longitude(), loc.city(), 90);
    }

    /**
     * Backfills historical data for a specific location and number of days.
     *
     * @param lat      latitude
     * @param lon      longitude
     * @param cityName display name
     * @param days     number of days to backfill
     * @return number of rows inserted
     */
    public int seedHistory(double lat, double lon, String cityName, int days) {
        LocalDate endDate = LocalDate.now().minusDays(1);
        LocalDate startDate = endDate.minusDays(days);

        log.info("Seeding {} days of history for {} ({}, {})", days, cityName, lat, lon);

        List<EnvironmentalSnapshot> snapshots = openMeteoClient.fetchHistorical(
                lat, lon, cityName, startDate, endDate);

        if (snapshots.isEmpty()) {
            log.warn("No historical data returned for {}", cityName);
            return 0;
        }

        // Compute derived signals sequentially
        EnvironmentalSnapshot previous = null;
        for (EnvironmentalSnapshot snapshot : snapshots) {
            derivedCalc.compute(snapshot, Optional.ofNullable(previous));
            previous = snapshot;
        }

        // Set user defaults and batch insert
        snapshots.forEach(this::withUserDefaults);
        snapshotRepo.saveAll(snapshots);

        log.info("Seeded {} historical snapshots for {}", snapshots.size(), cityName);
        return snapshots.size();
    }

    private EnvironmentalSnapshot withUserDefaults(EnvironmentalSnapshot s) {
        if (s.getUserId() == null || s.getUserId().isEmpty()) {
            return EnvironmentalSnapshot.builder()
                    .recordedAt(s.getRecordedAt())
                    .userId("ashoo-user")
                    .locationId(s.getLocationId())
                    .latitude(s.getLatitude())
                    .longitude(s.getLongitude())
                    .cityName(s.getCityName())
                    .pm25(s.getPm25()).pm10(s.getPm10()).o3(s.getO3())
                    .no2(s.getNo2()).so2(s.getSo2()).co(s.getCo())
                    .pollenAlder(s.getPollenAlder()).pollenBirch(s.getPollenBirch())
                    .pollenGrass(s.getPollenGrass()).pollenMugwort(s.getPollenMugwort())
                    .pollenOlive(s.getPollenOlive()).pollenRagweed(s.getPollenRagweed())
                    .temperatureC(s.getTemperatureC()).humidityPct(s.getHumidityPct())
                    .pressureMslHpa(s.getPressureMslHpa())
                    .windSpeedMs(s.getWindSpeedMs()).windGustsMs(s.getWindGustsMs())
                    .pm25RateOfChange(s.getPm25RateOfChange())
                    .pressureDrop3h(s.getPressureDrop3h())
                    .cumulativePm2524h(s.getCumulativePm2524h())
                    .aqiComputed(s.getAqiComputed())
                    .thunderstormFlag(s.getThunderstormFlag())
                    .dataSource(s.getDataSource())
                    .dataOrigin(s.getDataOrigin())
                    .build();
        }
        return s;
    }
}
