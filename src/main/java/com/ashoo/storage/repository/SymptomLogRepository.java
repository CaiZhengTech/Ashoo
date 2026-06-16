package com.ashoo.storage.repository;

import com.ashoo.storage.entity.SymptomLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Data access for the {@code symptom_log} hypertable using JdbcTemplate.
 *
 * The hypertable does have an {@code id BIGSERIAL} column, but the presence of
 * {@code BIGINT[]} (medications_used) requires manual array handling that
 * Spring Data JDBC's auto-mapping doesn't support cleanly. JdbcTemplate gives
 * us full control over array creation via the JDBC Connection object.
 */
@Repository
public class SymptomLogRepository {

    private final JdbcTemplate jdbc;

    public SymptomLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a new symptom log entry and returns the generated ID.
     *
     * Uses KeyHolder to capture the auto-generated BIGSERIAL id, which is
     * needed so the caller can reference this entry in a RecalibrationEvent.
     *
     * @param log the symptom log to insert
     * @return the persisted entry with its generated id set
     */
    public SymptomLog save(SymptomLog log) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO symptom_log
                        (logged_at, created_at, updated_at, user_id, severity, notes,
                         location_id, city_name, medications_used, data_origin)
                    VALUES (?, NOW(), NOW(), ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setTimestamp(1, Timestamp.from(log.getLoggedAt()));
            ps.setString(2, log.getUserId());
            ps.setInt(3, log.getSeverity());
            ps.setString(4, log.getNotes());
            setNullableLong(ps, 5, log.getLocationId());
            ps.setString(6, log.getCityName());
            ps.setArray(7, toSqlArray(con, log.getMedicationsUsed()));
            ps.setString(8, log.getDataOrigin() != null ? log.getDataOrigin() : "REAL");
            return ps;
        }, keyHolder);

        if (keyHolder.getKeys() != null) {
            Object idVal = keyHolder.getKeys().get("id");
            if (idVal instanceof Number n) {
                log.setId(n.longValue());
            }
        }
        return log;
    }

    /**
     * Batch-inserts multiple symptom log entries.
     *
     * Used exclusively by the demo seeder to insert 90 days of synthetic logs
     * in a single batch rather than individual round-trips.
     *
     * @param logs the list of entries to insert
     */
    public void saveAll(List<SymptomLog> logs) {
        jdbc.batchUpdate("""
                INSERT INTO symptom_log
                    (logged_at, created_at, updated_at, user_id, severity, notes,
                     location_id, city_name, medications_used, data_origin)
                VALUES (?, NOW(), NOW(), ?, ?, ?, ?, ?, ?, ?)
                """, logs, logs.size(), (ps, log) -> {
            ps.setTimestamp(1, Timestamp.from(log.getLoggedAt()));
            ps.setString(2, log.getUserId());
            ps.setInt(3, log.getSeverity());
            ps.setString(4, log.getNotes());
            setNullableLong(ps, 5, log.getLocationId());
            ps.setString(6, log.getCityName());
            ps.setArray(7, toSqlArray(ps.getConnection(), log.getMedicationsUsed()));
            ps.setString(8, log.getDataOrigin() != null ? log.getDataOrigin() : "SEEDED_SYNTHETIC");
        });
    }

    /**
     * Updates a symptom log entry in place.
     *
     * Returns the number of rows updated (1 on success, 0 if the entry
     * does not belong to the specified user — prevents cross-user edits).
     *
     * @param log the updated entry (id must be set)
     * @return rows updated
     */
    public int update(SymptomLog log) {
        return jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    UPDATE symptom_log
                    SET logged_at = ?, severity = ?, notes = ?,
                        location_id = ?, city_name = ?, medications_used = ?, updated_at = NOW()
                    WHERE id = ? AND user_id = ?
                    """);
            ps.setTimestamp(1, Timestamp.from(log.getLoggedAt()));
            ps.setInt(2, log.getSeverity());
            ps.setString(3, log.getNotes());
            setNullableLong(ps, 4, log.getLocationId());
            ps.setString(5, log.getCityName());
            ps.setArray(6, toSqlArray(con, log.getMedicationsUsed()));
            ps.setLong(7, log.getId());
            ps.setString(8, log.getUserId());
            return ps;
        });
    }

    /**
     * Finds a single symptom log entry by id and user.
     *
     * @param id     the entry id
     * @param userId the user identifier (prevents cross-user access)
     * @return the entry, or empty if not found
     */
    public Optional<SymptomLog> findByIdAndUserId(Long id, String userId) {
        List<SymptomLog> results = jdbc.query(
                "SELECT * FROM symptom_log WHERE id = ? AND user_id = ? LIMIT 1",
                ROW_MAPPER, id, userId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * Returns symptom log entries within a date range, newest first.
     *
     * @param userId the user identifier
     * @param from   inclusive start
     * @param to     inclusive end
     * @return matching entries
     */
    public List<SymptomLog> findByDateRange(String userId, Instant from, Instant to) {
        return jdbc.query(
                "SELECT * FROM symptom_log WHERE user_id = ? AND logged_at >= ? AND logged_at <= ? ORDER BY logged_at DESC",
                ROW_MAPPER, userId, Timestamp.from(from), Timestamp.from(to));
    }

    /**
     * Returns all symptom log entries for a user, newest first.
     *
     * @param userId the user identifier
     * @return all entries
     */
    public List<SymptomLog> findAllByUserId(String userId) {
        return jdbc.query(
                "SELECT * FROM symptom_log WHERE user_id = ? ORDER BY logged_at DESC",
                ROW_MAPPER, userId);
    }

    /**
     * Deletes an entry by id for a given user.
     *
     * @param id     the entry id
     * @param userId the user identifier
     * @return rows deleted (0 if not found or wrong user)
     */
    public int deleteByIdAndUserId(Long id, String userId) {
        return jdbc.update("DELETE FROM symptom_log WHERE id = ? AND user_id = ?", id, userId);
    }

    /**
     * Returns the count of distinct days with at least one symptom log entry (severity >= 1).
     *
     * Used by the correlation engine to determine confidence level.
     *
     * @param userId the user identifier
     * @return number of symptom days
     */
    public int countSymptomDays(String userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT DATE(logged_at)) FROM symptom_log WHERE user_id = ? AND severity >= 1",
                Integer.class, userId);
        return count != null ? count : 0;
    }

    private static final RowMapper<SymptomLog> ROW_MAPPER = (rs, rowNum) -> {
        SymptomLog log = new SymptomLog();
        log.setId(rs.getLong("id"));
        log.setLoggedAt(rs.getTimestamp("logged_at").toInstant());
        log.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        log.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        log.setUserId(rs.getString("user_id"));
        log.setSeverity(rs.getInt("severity"));
        log.setNotes(rs.getString("notes"));
        long locId = rs.getLong("location_id");
        log.setLocationId(rs.wasNull() ? null : locId);
        log.setCityName(rs.getString("city_name"));
        Array medArray = rs.getArray("medications_used");
        if (medArray != null) {
            Long[] ids = Arrays.stream((Object[]) medArray.getArray())
                    .map(o -> ((Number) o).longValue())
                    .toArray(Long[]::new);
            log.setMedicationsUsed(ids);
        }
        log.setDataOrigin(rs.getString("data_origin"));
        return log;
    };

    private static Array toSqlArray(Connection con, Long[] values) throws SQLException {
        if (values == null || values.length == 0) {
            return con.createArrayOf("bigint", new Long[0]);
        }
        return con.createArrayOf("bigint", values);
    }

    private static void setNullableLong(PreparedStatement ps, int idx, Long value) throws SQLException {
        if (value == null) ps.setNull(idx, Types.BIGINT);
        else ps.setLong(idx, value);
    }
}
