package com.loofie.core.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.loofie.core.messaging.IncomingEvent;

import tools.jackson.databind.json.JsonMapper;

/** Test-only consumer on test.inbox.v1 that always fails, to exercise retries and the DLT. */
@Component
public class AlwaysFailingTestListener {

    public static final String TOPIC = "test.inbox.v1";

    private final JsonMapper json;
    private final Map<String, Integer> attemptsByKey = new ConcurrentHashMap<>();

    public AlwaysFailingTestListener(JsonMapper json) {
        this.json = json;
    }

    @KafkaListener(topics = TOPIC, groupId = "test-always-failing")
    void on(ConsumerRecord<String, String> record) {
        attemptsByKey.merge(record.key(), 1, Integer::sum);
        IncomingEvent.parse(json, record.value()); // a broken envelope fails here, without retries
        throw new IllegalStateException("simulated handler failure");
    }

    public int attempts(String key) {
        return attemptsByKey.getOrDefault(key, 0);
    }
}
