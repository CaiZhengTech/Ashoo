package com.ashoo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that all Flyway migrations run cleanly against a real TimescaleDB container
 * and produce the expected schema.
 *
 * We use Testcontainers (not H2) because TimescaleDB-specific DDL —
 * {@code create_hypertable()}, {@code add_retention_policy()}, and
 * {@code add_continuous_aggregate_policy()} — simply does not exist in H2.
 * Tests against H2 would pass while the real database fails, which is precisely
 * the false-confidence trap we are trying to avoid.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Confirms that every domain table created by migrations V2–V7 is present.
     *
     * We query {@code information_schema.tables} (standard SQL, not TimescaleDB-specific)
     * so this test would also catch a complete failure of the TimescaleDB extension —
     * in that case, the hypertable conversions in V2–V4 would fail before we even get here.
     */
    @Test
    void allExpectedTablesExist() {
        List<String> tables = List.of(
            "environmental_snapshot",
            "symptom_log",
            "risk_score_history",
            "saved_location",
            "recent_search",
            "medication",
            "reminder_rule",
            "consent_record",
            "recalibration_event",
            "correlation_result",
            "briefing_log"
        );
        for (String table : tables) {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = 'public' AND table_name = ?",
                Integer.class, table);
            assertThat(count)
                .as("Table '%s' should exist after migrations", table)
                .isEqualTo(1);
        }
    }

    /**
     * Confirms that the three time-series tables are registered as TimescaleDB hypertables.
     *
     * A hypertable is a TimescaleDB concept: it looks like a regular Postgres table
     * but is internally partitioned by time. We verify via
     * {@code timescaledb_information.hypertables}, which only exists when the
     * TimescaleDB extension is loaded — making this an implicit extension health check too.
     */
    @Test
    void threeHypertablesAreRegistered() {
        List<String> expected = List.of(
            "environmental_snapshot",
            "symptom_log",
            "risk_score_history"
        );
        for (String ht : expected) {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM timescaledb_information.hypertables " +
                "WHERE hypertable_name = ?",
                Integer.class, ht);
            assertThat(count)
                .as("Hypertable '%s' should be registered", ht)
                .isEqualTo(1);
        }
    }

    /**
     * Confirms that the daily environmental average continuous aggregate was created by V8.
     *
     * A continuous aggregate is a TimescaleDB materialized view that is refreshed
     * automatically on a schedule. Verifying its existence here ensures the full V8
     * migration ran successfully, including the {@code add_continuous_aggregate_policy()} call.
     */
    @Test
    void continuousAggregateIsRegistered() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM timescaledb_information.continuous_aggregates " +
            "WHERE view_name = 'daily_environmental_avg'",
            Integer.class);
        assertThat(count)
            .as("Continuous aggregate 'daily_environmental_avg' should exist after V8 migration")
            .isEqualTo(1);
    }
}
