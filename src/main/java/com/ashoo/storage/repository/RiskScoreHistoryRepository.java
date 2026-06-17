package com.ashoo.storage.repository;

import com.ashoo.storage.entity.RiskScoreHistory;
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
 * Data access for the {@code risk_score_history} hypertable using JdbcTemplate.
 *
 * JdbcTemplate (not Spring Data JDBC) is required for two reasons: the hypertable
 * has no primary key, and the {@code factor_scores} column is JSONB, which needs an
 * explicit {@code ::jsonb} cast on insert that Spring Data's auto-mapping won't do.
 */
@Repository
public class RiskScoreHistoryRepository {

    private final JdbcTemplate jdbc;

    public RiskScoreHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts one risk-score reading.
     *
     * The {@code ?::jsonb} cast tells Postgres to parse the factor-scores string as
     * JSONB; passing a plain string to a JSONB column without it raises a type error.
     *
     * @param r the reading to persist
     */
    public void save(RiskScoreHistory r) {
        jdbc.update("""
                INSERT INTO risk_score_history
                    (scored_at, user_id, risk_score, risk_score_smoothed, risk_label,
                     alert_triggered, factor_scores, confidence_level, symptom_days_available)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                Timestamp.from(r.getScoredAt()), r.getUserId(),
                r.getRiskScore(), r.getRiskScoreSmoothed(), r.getRiskLabel(),
                r.getAlertTriggered(), r.getFactorScores(),
                r.getConfidenceLevel(), r.getSymptomDaysAvailable());
    }

    /**
     * Returns the most recent score for a user — the prior state the next EWMA
     * and hysteresis step needs.
     *
     * @param userId the user identifier
     * @return the latest reading, or empty if the user has never been scored
     */
    public Optional<RiskScoreHistory> findLatest(String userId) {
        List<RiskScoreHistory> results = jdbc.query(
                "SELECT * FROM risk_score_history WHERE user_id = ? ORDER BY scored_at DESC LIMIT 1",
                ROW_MAPPER, userId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * Returns scores within a time range, oldest first — the shape a trend chart wants.
     *
     * @param userId the user identifier
     * @param from   inclusive start
     * @param to     inclusive end
     * @return matching readings ordered by time ascending
     */
    public List<RiskScoreHistory> findByDateRange(String userId, Instant from, Instant to) {
        return jdbc.query(
                "SELECT * FROM risk_score_history WHERE user_id = ? AND scored_at >= ? AND scored_at <= ? ORDER BY scored_at ASC",
                ROW_MAPPER, userId, Timestamp.from(from), Timestamp.from(to));
    }

    private static final RowMapper<RiskScoreHistory> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private static RiskScoreHistory mapRow(ResultSet rs) throws SQLException {
        RiskScoreHistory r = new RiskScoreHistory();
        r.setScoredAt(rs.getTimestamp("scored_at").toInstant());
        r.setUserId(rs.getString("user_id"));
        r.setRiskScore(getBoxedDouble(rs, "risk_score"));
        r.setRiskScoreSmoothed(getBoxedDouble(rs, "risk_score_smoothed"));
        r.setRiskLabel(rs.getString("risk_label"));
        boolean alert = rs.getBoolean("alert_triggered");
        r.setAlertTriggered(rs.wasNull() ? null : alert);
        r.setFactorScores(rs.getString("factor_scores"));
        r.setConfidenceLevel(rs.getString("confidence_level"));
        int days = rs.getInt("symptom_days_available");
        r.setSymptomDaysAvailable(rs.wasNull() ? null : days);
        return r;
    }

    private static Double getBoxedDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }
}
