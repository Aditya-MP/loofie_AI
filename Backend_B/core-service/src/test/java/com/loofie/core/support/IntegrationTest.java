package com.loofie.core.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full app against PostgreSQL (schema core_test), an embedded Kafka broker and a
 * controllable clock. All integration tests share this one Spring context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@ActiveProfiles({"local", "test"})
@Import(TestSupportConfig.class)
@EmbeddedKafka(partitions = 3,
        topics = {"core.job.v1", "core.job.v1.DLT", "test.inbox.v1", "test.inbox.v1.DLT"},
        brokerProperties = {"auto.create.topics.enable=false"})
public @interface IntegrationTest {
}
