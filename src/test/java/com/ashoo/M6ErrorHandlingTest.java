package com.ashoo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web-layer integration test for {@link com.ashoo.api.GlobalExceptionHandler}, against a
 * real TimescaleDB and a live HTTP port.
 *
 * Drives real endpoints into their failure modes and confirms each returns the consistent
 * {@link com.ashoo.api.dto.ErrorResponse} shape with the right status — the consent gate
 * (403), a missing required parameter (400), and an unparseable parameter (400).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class M6ErrorHandlingTest {

    @Autowired private TestRestTemplate rest;

    @Test
    void consentGate_returns403WithConsistentBody() {
        // ashoo-user has not consented in this suite → reminders endpoint must 403.
        ResponseEntity<String> response =
                rest.getForEntity("/api/v1/reminders/current", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"status\":403");
        assertThat(response.getBody()).contains("\"error\":\"Forbidden\"");
        assertThat(response.getBody()).contains("/api/v1/reminders/current");
    }

    @Test
    void missingRequiredParam_returns400() {
        // /export requires from & to.
        ResponseEntity<String> response =
                rest.getForEntity("/api/v1/export", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"status\":400");
        assertThat(response.getBody()).contains("Missing required parameter");
    }

    @Test
    void unparseableParam_returns400() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/v1/risk/history?from=not-a-date&to=also-bad", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"status\":400");
    }
}
