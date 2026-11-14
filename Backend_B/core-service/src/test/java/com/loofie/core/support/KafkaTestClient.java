package com.loofie.core.support;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Reads records from the embedded broker in tests. */
@Component
public class KafkaTestClient {

    private final String brokers;

    public KafkaTestClient(@Value("${spring.embedded.kafka.brokers}") String brokers) {
        this.brokers = brokers;
    }

    /** Polls {@code topic} from the beginning until {@code expected} matching records arrive or the timeout passes. */
    public List<ConsumerRecord<String, String>> read(String topic, Predicate<ConsumerRecord<String, String>> match,
                                                     int expected, Duration timeout) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(timeout);
            while (found.size() < expected && Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
                    if (match.test(r)) {
                        found.add(r);
                    }
                }
            }
        }
        return found;
    }
}
