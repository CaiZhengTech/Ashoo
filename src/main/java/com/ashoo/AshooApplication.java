package com.ashoo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Ashoo backend.
 *
 * {@code @EnableScheduling} activates Spring's scheduling infrastructure so that
 * {@code @Scheduled} methods (the hourly ingestion cycle, rate-limiter resets) are
 * picked up automatically. With {@code spring.threads.virtual.enabled=true} in
 * application.yml, those scheduled tasks run on virtual threads instead of OS threads —
 * the correct choice for an I/O-bound polling workload.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class AshooApplication {

    public static void main(String[] args) {
        SpringApplication.run(AshooApplication.class, args);
    }
}
