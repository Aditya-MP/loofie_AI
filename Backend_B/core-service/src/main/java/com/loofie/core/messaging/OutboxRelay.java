package com.loofie.core.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Publishes outbox rows to Kafka. Delivery is at-least-once; consumers de-duplicate on eventId.
 *
 * <p><b>No database transaction is open while talking to Kafka.</b> A run reads a batch, sends it,
 * then marks the results in short statements, so a slow or dead broker can never hold a connection
 * or row locks.
 *
 * <p><b>Order.</b> Rows are read in {@code seq} order (a database sequence, never a timestamp).
 * Rows with the same key (one job) are sent strictly one after another, even across topics: the
 * next one only after the broker acked the previous. Different keys go out in parallel, in "waves" (the 1st row of
 * every key, then the 2nd, ...). If a row fails, the rest of its key waits, and while it is on
 * backoff the query itself skips every later row of that key.
 *
 * <p><b>Failures.</b> A failed row gets {@code next_attempt_at} with exponential backoff (1s, 2s,
 * 4s, ... capped at {@link #MAX_BACKOFF}). If a send fails straight away (broker unreachable, so
 * the producer could not even get metadata), the run stops: there is no point trying 100 rows
 * against a dead broker.
 *
 * <p><b>Instances.</b> Only the instance holding the "outbox-relay" lease runs this (see
 * {@link OutboxRelayScheduler}); otherwise two relays could interleave one key's rows.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 2000;
    static final Duration MAX_BACKOFF = Duration.ofSeconds(60);
    /** Bounds one run (each wave waits at most sendTimeout); a key's remaining rows go next run. */
    static final int MAX_WAVES = 10;

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties props;
    private final Clock clock;
    private final Counter published;
    private final Counter failed;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, OutboxProperties props, Clock clock,
                       MeterRegistry meters) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.props = props;
        this.clock = clock;
        this.published = Counter.builder("loofie.outbox.published").description("Outbox rows acked by Kafka")
                .register(meters);
        this.failed = Counter.builder("loofie.outbox.failures").description("Outbox send attempts that failed")
                .register(meters);
    }

    /** Publishes one batch. Returns how many rows were published. */
    public int publishBatch() {
        Instant now = clock.instant();
        List<Row> rows = readBatch(now);
        if (rows.isEmpty()) {
            return 0;
        }

        // Group by key (a job id), keeping seq order inside each group. Across topics too: one job's events
        // never overtake each other, whichever topic they go to.
        Map<String, List<Row>> byKey = new LinkedHashMap<>();
        for (Row row : rows) {
            byKey.computeIfAbsent(row.key(), k -> new ArrayList<>()).add(row);
        }

        int sent = 0;
        List<List<Row>> alive = new ArrayList<>(byKey.values());
        for (int wave = 0; !alive.isEmpty() && wave < MAX_WAVES; wave++) {
            List<InFlight> inFlight = new ArrayList<>();
            boolean brokerDown = false;
            for (List<Row> group : alive) {
                Row row = group.get(wave);
                CompletableFuture<SendResult<String, String>> future;
                try {
                    future = send(row);
                } catch (RuntimeException e) {
                    future = CompletableFuture.failedFuture(e);
                }
                inFlight.add(new InFlight(group, row, future));
                if (future.isCompletedExceptionally()) {
                    brokerDown = true; // failed without reaching the broker: stop issuing more sends
                    break;
                }
            }

            List<UUID> ok = new ArrayList<>();
            List<List<Row>> next = new ArrayList<>();
            long deadline = System.nanoTime() + props.sendTimeout().toNanos();
            for (InFlight f : inFlight) {
                Throwable error = await(f.future(), deadline);
                if (error == null) {
                    ok.add(f.row().id());
                    if (f.group().size() > wave + 1) {
                        next.add(f.group()); // its next row may go in the next wave
                    }
                } else {
                    recordFailure(f.row(), error, now);
                }
            }
            markPublished(ok);
            sent += ok.size();
            if (brokerDown) {
                break;
            }
            alive = next;
        }
        return sent;
    }

    private List<Row> readBatch(Instant now) {
        return jdbc.sql("""
                SELECT o.id, o.topic, o.message_key, o.event_type, o.envelope::text AS envelope, o.attempts
                FROM outbox o
                WHERE o.published_at IS NULL
                  AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= :now)
                  AND NOT EXISTS (
                      SELECT 1 FROM outbox b
                      WHERE b.published_at IS NULL AND b.next_attempt_at > :now
                        AND b.message_key = o.message_key AND b.seq < o.seq)
                ORDER BY o.seq
                LIMIT :limit
                """)
                .param("now", utc(now))
                .param("limit", props.batchSize())
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("topic"),
                        rs.getString("message_key"), rs.getString("event_type"), rs.getString("envelope"),
                        rs.getInt("attempts")))
                .list();
    }

    private CompletableFuture<SendResult<String, String>> send(Row row) {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.key(), row.envelope());
        record.headers().add("eventId", row.id().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
        return kafka.send(record);
    }

    /** Waits for the ack until the wave's deadline. Returns null on success, else the error. */
    private static Throwable await(CompletableFuture<?> future, long deadlineNanos) {
        try {
            future.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause() != null ? e.getCause() : e;
        } catch (TimeoutException e) {
            return e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return e;
        }
    }

    private void markPublished(List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("""
                UPDATE outbox SET published_at = :now, attempts = attempts + 1, last_error = NULL, next_attempt_at = NULL
                WHERE id IN (:ids)
                """)
                .param("now", utc(clock.instant()))
                .param("ids", ids)
                .update();
        published.increment(ids.size());
    }

    private void recordFailure(Row row, Throwable error, Instant now) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        if (message.length() > MAX_ERROR_LENGTH) {
            message = message.substring(0, MAX_ERROR_LENGTH);
        }
        int attempts = row.attempts() + 1;
        Instant retryAt = now.plus(backoff(attempts));
        log.warn("Outbox publish failed for {} ({} -> {}), attempt {}, retry at {}: {}", row.id(), row.eventType(),
                row.topic(), attempts, retryAt, message);
        jdbc.sql("UPDATE outbox SET attempts = :attempts, last_error = :error, next_attempt_at = :retryAt WHERE id = :id")
                .param("attempts", attempts)
                .param("error", message)
                .param("retryAt", utc(retryAt))
                .param("id", row.id())
                .update();
        failed.increment();
    }

    /** 1s after the first failure, doubling, capped at {@link #MAX_BACKOFF}. */
    static Duration backoff(int attempts) {
        int exponent = Math.min(Math.max(attempts - 1, 0), 10);
        Duration d = Duration.ofSeconds(1L << exponent);
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record Row(UUID id, String topic, String key, String eventType, String envelope, int attempts) {
    }

    private record InFlight(List<Row> group, Row row, CompletableFuture<SendResult<String, String>> future) {
    }
}
