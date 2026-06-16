package com.ashoo.storage.repository;

import com.ashoo.storage.entity.EnvironmentalSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Data access for the {@code environmental_snapshot} hypertable.
 *
 * Uses JdbcTemplate directly instead of Spring Data JDBC's CrudRepository
 * because the hypertable has no primary key — TimescaleDB partitions by
 * {@code recorded_at} rather than an ID column. Spring Data JDBC requires
 * an {@code @Id} field to manage entities, so JdbcTemplate is the right tool here.
 */
@Repository
public class EnvironmentalSnapshotRepository {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentalSnapshotRepository.class);

    private final JdbcTemplate jdbc;

    public EnvironmentalSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String INSERT_SQL = """
            INSERT INTO environmental_snapshot (
                recorded_at, user_id, location_id, latitude, longitude, city_name,
                pm25, pm10, o3, no2, so2, co,
                pollen_alder, pollen_birch, pollen_grass, pollen_mugwort, pollen_olive, pollen_ragweed,
                temperature_c, humidity_pct, pressure_msl_hpa, wind_speed_ms, wind_gusts_ms,
                pm25_rate_of_change, pressure_drop_3h, cumulative_pm25_24h, aqi_computed, thunderstorm_flag,
                data_source, data_origin
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * Inserts a single snapshot row into the hypertable.
     *
     * @param s the snapshot to persist
     */
    public void save(EnvironmentalSnapshot s) {
        jdbc.update(INSERT_SQL, params(s));
    }

    /**
     * Batch-inserts multiple snapshots in one database round-trip.
     *
     * Uses {@link JdbcTemplate#batchUpdate} which sends all rows in a single
     * JDBC batch — dramatically faster than individual inserts when backfilling
     * 90 days of hourly data (~2,160 rows).
     *
     * @param snapshots the list of snapshots to persist
     */
    public void saveAll(List<EnvironmentalSnapshot> snapshots) {
        jdbc.batchUpdate(INSERT_SQL, snapshots.stream()
                .map(EnvironmentalSnapshotRepository::params)
                .toList());
    }

    /**
     * Returns the most recent snapshot for a given user.
     *
     * @param userId the user identifier
     * @return the latest snapshot, or empty if no data exists yet
     */
    public Optional<EnvironmentalSnapshot> findLatest(String userId) {
        List<EnvironmentalSnapshot> results = jdbc.query(
                "SELECT * FROM environmental_snapshot WHERE user_id = ? ORDER BY recorded_at DESC LIMIT 1",
                ROW_MAPPER, userId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * Returns snapshots within a time range, ordered newest-first.
     *
     * @param userId the user identifier
     * @param from   inclusive start of the range
     * @param to     inclusive end of the range
     * @return matching snapshots, or empty list
     */
    public List<EnvironmentalSnapshot> findByDateRange(String userId, Instant from, Instant to) {
        return jdbc.query(
                "SELECT * FROM environmental_snapshot WHERE user_id = ? AND recorded_at >= ? AND recorded_at <= ? ORDER BY recorded_at DESC",
                ROW_MAPPER, userId, Timestamp.from(from), Timestamp.from(to));
    }

    /**
     * Returns the snapshot immediately before the given timestamp for a user.
     *
     * Used by the derived-signal calculator to compute deltas (rate of change,
     * pressure drop) between consecutive readings.
     *
     * @param userId the user identifier
     * @param before the timestamp to look before
     * @return the previous snapshot, or empty if this is the first reading
     */
    public Optional<EnvironmentalSnapshot> findPrevious(String userId, Instant before) {
        List<EnvironmentalSnapshot> results = jdbc.query(
                "SELECT * FROM environmental_snapshot WHERE user_id = ? AND recorded_at < ? ORDER BY recorded_at DESC LIMIT 1",
                ROW_MAPPER, userId, Timestamp.from(before));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * Returns the average PM2.5 over the last 24 hours for cumulative burden calculation.
     *
     * @param userId the user identifier
     * @param asOf   the reference timestamp
     * @return average PM2.5, or null if no data in that window
     */
    public Double findAveragePm25Last24h(String userId, Instant asOf) {
        return jdbc.queryForObject(
                "SELECT AVG(pm25) FROM environmental_snapshot WHERE user_id = ? AND recorded_at >= ? AND recorded_at <= ?",
                Double.class, userId, Timestamp.from(asOf.minusSeconds(86400)), Timestamp.from(asOf));
    }

    private static Object[] params(EnvironmentalSnapshot s) {
        return new Object[]{
                Timestamp.from(s.getRecordedAt()), s.getUserId(), s.getLocationId(),
                s.getLatitude(), s.getLongitude(), s.getCityName(),
                s.getPm25(), s.getPm10(), s.getO3(), s.getNo2(), s.getSo2(), s.getCo(),
                s.getPollenAlder(), s.getPollenBirch(), s.getPollenGrass(),
                s.getPollenMugwort(), s.getPollenOlive(), s.getPollenRagweed(),
                s.getTemperatureC(), s.getHumidityPct(), s.getPressureMslHpa(),
                s.getWindSpeedMs(), s.getWindGustsMs(),
                s.getPm25RateOfChange(), s.getPressureDrop3h(), s.getCumulativePm2524h(),
                s.getAqiComputed(), s.getThunderstormFlag(),
                s.getDataSource(), s.getDataOrigin()
        };
    }

    private static final RowMapper<EnvironmentalSnapshot> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private static EnvironmentalSnapshot mapRow(ResultSet rs) throws SQLException {
        return EnvironmentalSnapshot.builder()
                .recordedAt(rs.getTimestamp("recorded_at").toInstant())
                .userId(rs.getString("user_id"))
                .locationId(getBoxedLong(rs, "location_id"))
                .latitude(getBoxedDouble(rs, "latitude"))
                .longitude(getBoxedDouble(rs, "longitude"))
                .cityName(rs.getString("city_name"))
                .pm25(getBoxedDouble(rs, "pm25"))
                .pm10(getBoxedDouble(rs, "pm10"))
                .o3(getBoxedDouble(rs, "o3"))
                .no2(getBoxedDouble(rs, "no2"))
                .so2(getBoxedDouble(rs, "so2"))
                .co(getBoxedDouble(rs, "co"))
                .pollenAlder(getBoxedDouble(rs, "pollen_alder"))
                .pollenBirch(getBoxedDouble(rs, "pollen_birch"))
                .pollenGrass(getBoxedDouble(rs, "pollen_grass"))
                .pollenMugwort(getBoxedDouble(rs, "pollen_mugwort"))
                .pollenOlive(getBoxedDouble(rs, "pollen_olive"))
                .pollenRagweed(getBoxedDouble(rs, "pollen_ragweed"))
                .temperatureC(getBoxedDouble(rs, "temperature_c"))
                .humidityPct(getBoxedDouble(rs, "humidity_pct"))
                .pressureMslHpa(getBoxedDouble(rs, "pressure_msl_hpa"))
                .windSpeedMs(getBoxedDouble(rs, "wind_speed_ms"))
                .windGustsMs(getBoxedDouble(rs, "wind_gusts_ms"))
                .pm25RateOfChange(getBoxedDouble(rs, "pm25_rate_of_change"))
                .pressureDrop3h(getBoxedDouble(rs, "pressure_drop_3h"))
                .cumulativePm2524h(getBoxedDouble(rs, "cumulative_pm25_24h"))
                .aqiComputed(getBoxedInt(rs, "aqi_computed"))
                .thunderstormFlag(getBoxedBool(rs, "thunderstorm_flag"))
                .dataSource(rs.getString("data_source"))
                .dataOrigin(rs.getString("data_origin"))
                .build();
    }

    private static Double getBoxedDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static Long getBoxedLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Integer getBoxedInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Boolean getBoxedBool(ResultSet rs, String col) throws SQLException {
        boolean v = rs.getBoolean(col);
        return rs.wasNull() ? null : v;
    }
}
