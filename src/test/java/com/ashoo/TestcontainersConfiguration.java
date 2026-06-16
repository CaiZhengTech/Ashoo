package com.ashoo;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Provides a real TimescaleDB container for all Spring integration tests.
 *
 * We use the official TimescaleDB Docker image rather than vanilla PostgreSQL
 * because our Flyway migrations call TimescaleDB-specific functions:
 * {@code create_hypertable()}, {@code add_retention_policy()}, and
 * {@code add_continuous_aggregate_policy()}. These do not exist in plain
 * PostgreSQL, so tests against a vanilla container would pass Flyway but fail
 * in production — exactly the false-confidence trap Testcontainers exists to prevent.
 *
 * {@code @ServiceConnection} tells Spring Boot to wire this container's
 * JDBC URL, username, and password into the DataSource automatically —
 * no manual property overrides needed.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> timescaleDbContainer() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("timescale/timescaledb:latest-pg16")
                               .asCompatibleSubstituteFor("postgres"));
    }
}
