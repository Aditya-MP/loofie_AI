package com.loofie.core.support;

import java.time.Instant;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestSupportConfig {

    public static final Instant START = Instant.parse("2026-11-16T04:30:00Z"); // Monday 10:00 IST

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(START);
    }

    /** Fresh schema per test run: drop everything in core_test, then apply all migrations. */
    @Bean
    FlywayMigrationStrategy cleanMigrate() {
        return flyway -> {
            flyway.clean();
            flyway.migrate();
        };
    }
}
