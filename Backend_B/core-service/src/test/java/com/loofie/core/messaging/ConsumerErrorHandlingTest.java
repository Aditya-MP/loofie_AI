package com.loofie.core.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.loofie.core.common.Ids;
import com.loofie.core.support.AlwaysFailingTestListener;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.KafkaTestClient;

import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class ConsumerErrorHandlingTest {

    @Autowired KafkaTemplate<String, String> template;
    @Autowired AlwaysFailingTestListener listener;
    @Autowired KafkaTestClient kafka;
    @Autowired JsonMapper json;
    @Autowired Inbox inbox;
    @Autowired TransactionTemplate tx;

    @Test
    void failingHandlerIsRetriedFiveTimesThenDeadLettered() throws Exception {
        String key = "retry-" + UUID.randomUUID();
        template.send(AlwaysFailingTestListener.TOPIC, key, validEnvelope()).get();

        List<ConsumerRecord<String, String>> dlt = kafka.read(AlwaysFailingTestListener.TOPIC + ".DLT",
                r -> key.equals(r.key()), 1, Duration.ofSeconds(30));

        assertThat(dlt).hasSize(1);
        assertThat(listener.attempts(key)).isEqualTo(5);
        assertThat(dlt.getFirst().headers().lastHeader("kafka_dlt-exception-message")).isNotNull();
    }

    @Test
    void brokenEnvelopeGoesStraightToTheDlt() throws Exception {
        String key = "broken-" + UUID.randomUUID();
        template.send(AlwaysFailingTestListener.TOPIC, key, "{\"eventType\":\"NoId\"}").get();

        List<ConsumerRecord<String, String>> dlt = kafka.read(AlwaysFailingTestListener.TOPIC + ".DLT",
                r -> key.equals(r.key()), 1, Duration.ofSeconds(30));

        assertThat(dlt).hasSize(1);
        assertThat(listener.attempts(key)).isEqualTo(1);
    }

    @Test
    void inboxAcceptsAnEventOnlyOnce() {
        UUID eventId = Ids.newId();
        Boolean first = tx.execute(s -> inbox.firstTime("test-consumer", eventId));
        Boolean again = tx.execute(s -> inbox.firstTime("test-consumer", eventId));
        Boolean otherConsumer = tx.execute(s -> inbox.firstTime("another-consumer", eventId));

        assertThat(first).isTrue();
        assertThat(again).isFalse();
        assertThat(otherConsumer).isTrue();
    }

    private String validEnvelope() {
        return json.writeValueAsString(new EventEnvelope(Ids.newId(), "TestEvent", 1, Instant.now(),
                "test", UUID.randomUUID(), 1, UUID.randomUUID(), null, Map.of("k", "v")).toMap());
    }
}
