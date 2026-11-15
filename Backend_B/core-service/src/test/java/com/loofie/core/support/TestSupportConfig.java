package com.loofie.core.support;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.loofie.core.messaging.AiFeedbackMapper;
import com.loofie.core.messaging.AiFeedbackProperties;
import com.loofie.core.messaging.AiFeedbackSender;
import com.loofie.core.messaging.EventSender;

import tools.jackson.databind.json.JsonMapper;

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

    @Bean(destroyMethod = "stop")
    FakeAiService fakeAiService(JsonMapper json) throws IOException {
        return new FakeAiService(json);
    }

    /** The real HTTP sender, pointed at the fake Backend A. */
    @Bean
    @Primary
    EventSender testEventSender(FakeAiService fake, AiFeedbackMapper mapper, JsonMapper json) {
        return new AiFeedbackSender(new AiFeedbackProperties(fake.url(), FakeAiService.API_KEY, Duration.ofSeconds(1),
                Duration.ofSeconds(2)), mapper, json);
    }
}
