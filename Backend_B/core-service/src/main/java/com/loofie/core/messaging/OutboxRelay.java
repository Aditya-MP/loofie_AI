package com.loofie.core.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox rows to Kafka.
 *
 * <p>Each run locks up to {@code batchSize} unpublished rows with {@code FOR UPDATE SKIP LOCKED}
 * (oldest first), sends them one by one, waiting for the broker ack, and marks each as published.
 * When a row fails it records the error and skips the rest of that key's rows for this run, so a
 * later event for the same job is never published before an earlier one, while other jobs' events
 * keep flowing. Rows with a growing {@code attempts} count need ops attention (alerting: TODO).
 * Delivery is at-least-once: if the app dies after
 * the ack but before the commit, the row is sent again, so consumers de-duplicate on eventId.
 *
 * <p>Ordering across instances: SKIP LOCKED lets two relays split a batch, which could reorder
 * events for one key. Run one relay until ShedLock is added with the second instance.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final OutboxProperties props;
    private final Clock clock;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       OutboxProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    /** Publishes one batch. Returns how many rows were published. */
    public int publishBatch() {
        Integer published = tx.execute(status -> {
            List<Row> rows = jdbc.sql("""
                    SELECT id, topic, message_key, event_type, envelope::text AS envelope
                    FROM outbox
                    WHERE published_at IS NULL
                    ORDER BY created_at, id
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                    """)
                    .param("limit", props.batchSize())
                    .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("topic"),
                            rs.getString("message_key"), rs.getString("event_type"), rs.getString("envelope")))
                    .list();

            int sent = 0;
            Set<String> blockedKeys = new HashSet<>();
            for (Row row : rows) {
                if (blockedKeys.contains(row.key())) {
                    continue; // keep per-key order: nothing after a failed row for the same key goes out
                }
                try {
                    send(row);
                } catch (Exception e) {
                    recordFailure(row, e);
                    blockedKeys.add(row.key());
                    continue;
                }
                jdbc.sql("UPDATE outbox SET published_at = :now, attempts = attempts + 1, last_error = NULL WHERE id = :id")
                        .param("now", OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
                        .param("id", row.id())
                        .update();
                sent++;
            }
            return sent;
        });
        return published == null ? 0 : published;
    }

    private void send(Row row) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.key(), row.envelope());
        record.headers().add("eventId", row.id().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(props.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void recordFailure(Row row, Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        if (message.length() > MAX_ERROR_LENGTH) {
            message = message.substring(0, MAX_ERROR_LENGTH);
        }
        log.warn("Outbox publish failed for {} ({} -> {}): {}", row.id(), row.eventType(), row.topic(), message);
        jdbc.sql("UPDATE outbox SET attempts = attempts + 1, last_error = :error WHERE id = :id")
                .param("error", message)
                .param("id", row.id())
                .update();
    }

    private record Row(UUID id, String topic, String key, String eventType, String envelope) {
    }
}
