package com.loofie.core.messaging;

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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.loofie.core.messaging.EventSender.Delivery;
import com.loofie.core.messaging.EventSender.Message;
import com.loofie.core.messaging.EventSender.Rejected;
import com.loofie.core.messaging.EventSender.Unreachable;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Sends outbox rows through an {@link EventSender} (Backend A over HTTP). At-least-once; the receiver
 * de-duplicates on the event id.
 *
 * <p><b>No database transaction is open while sending.</b> A run reads a batch, sends it, then marks the
 * results in short statements, so a slow or dead receiver can never hold a connection or row locks.
 *
 * <p><b>Order.</b> Rows are read in {@code seq} order (a database sequence, never a timestamp). Rows with the
 * same key (one job) are sent strictly one after another: the next only after the previous was answered.
 * Different keys go out in parallel, in "waves" (the 1st row of every key, then the 2nd, ...), at most
 * {@code maxInFlight} requests at once. If a row fails, the rest of its key waits, and while it is on backoff
 * the query itself skips every later row of that key.
 *
 * <p><b>Failures.</b> A failed row gets {@code next_attempt_at} with exponential backoff (1s, 2s, 4s, ...
 * capped at {@link #MAX_BACKOFF}). If the receiver is unreachable the run stops: no point trying 100 rows
 * against a dead server. A row the receiver will never accept is <b>parked</b> ({@code parked_at}): out of the
 * queue with its error kept, so it cannot block its job's later events forever. Requeue after a fix with
 * {@code UPDATE outbox SET parked_at = NULL, attempts = 0, last_error = NULL WHERE parked_at IS NOT NULL ...}.
 *
 * <p><b>Instances.</b> Only the instance holding the "outbox-relay" lease runs this (see
 * {@link OutboxRelayScheduler}); otherwise two relays could interleave one key's rows. A run stops starting
 * new sends after {@link #MAX_WAVES} x sendTimeout, so it always ends inside the lease.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 2000;
    static final Duration MAX_BACKOFF = Duration.ofSeconds(60);
    /** Bounds one run; a key's remaining rows go next run. */
    static final int MAX_WAVES = 10;

    private final JdbcClient jdbc;
    private final EventSender sender;
    private final OutboxProperties props;
    private final Clock clock;
    private final Counter published;
    private final Counter skipped;
    private final Counter conflicts;
    private final Counter failed;
    private final Counter parked;

    public OutboxRelay(JdbcClient jdbc, EventSender sender, OutboxProperties props, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.props = props;
        this.clock = clock;
        this.published = Counter.builder("loofie.outbox.published").description("Outbox rows accepted by the receiver")
                .register(meters);
        this.skipped = Counter.builder("loofie.outbox.skipped").description("Outbox rows the receiver does not take")
                .register(meters);
        this.conflicts = Counter.builder("loofie.outbox.conflicts")
                .description("Rows the receiver already had with different content (a mapping change)").register(meters);
        this.failed = Counter.builder("loofie.outbox.failures").description("Outbox send attempts that failed")
                .register(meters);
        // Not "loofie.outbox.parked.total": Prometheus would map it onto the loofie.outbox.parked gauge's name.
        this.parked = Counter.builder("loofie.outbox.rejected").description("Rows the receiver rejected for good")
                .register(meters);
    }

    /** Sends one batch. Returns how many rows were finished (delivered, skipped or parked). */
    public int publishBatch() {
        Instant now = clock.instant();
        List<Row> rows = readBatch(now);
        if (rows.isEmpty()) {
            return 0;
        }

        // Group by key (a job id), keeping seq order inside each group.
        Map<String, List<Row>> byKey = new LinkedHashMap<>();
        for (Row row : rows) {
            byKey.computeIfAbsent(row.key(), k -> new ArrayList<>()).add(row);
        }

        long runDeadline = System.nanoTime() + props.sendTimeout().multipliedBy(MAX_WAVES).toNanos();
        int finished = 0;
        boolean stop = false;
        List<List<Row>> alive = new ArrayList<>(byKey.values());
        for (int wave = 0; !alive.isEmpty() && wave < MAX_WAVES && !stop; wave++) {
            List<List<Row>> next = new ArrayList<>();
            for (int from = 0; from < alive.size() && !stop; from += props.maxInFlight()) {
                if (System.nanoTime() > runDeadline) {
                    stop = true;
                    break;
                }
                List<List<Row>> chunk = alive.subList(from, Math.min(alive.size(), from + props.maxInFlight()));
                Outcomes o = sendChunk(chunk, wave, now);
                finished += o.finished();
                next.addAll(o.continuing());
                stop = o.unreachable();
            }
            alive = next;
        }
        return finished;
    }

    /** Sends one row of each group at once, waits for all answers, records them. */
    private Outcomes sendChunk(List<List<Row>> chunk, int wave, Instant now) {
        List<InFlight> inFlight = new ArrayList<>(chunk.size());
        for (List<Row> group : chunk) {
            Row row = group.get(wave);
            CompletableFuture<Delivery> future;
            try {
                future = sender.send(row.message());
            } catch (RuntimeException e) {
                future = CompletableFuture.failedFuture(e);
            }
            inFlight.add(new InFlight(group, row, future));
        }

        List<UUID> done = new ArrayList<>();
        List<List<Row>> continuing = new ArrayList<>();
        boolean unreachable = false;
        int parkedNow = 0;
        long deadline = System.nanoTime() + props.sendTimeout().toNanos();
        for (InFlight f : inFlight) {
            Answer answer = await(f.future(), deadline);
            if (answer.delivery() != null) {
                done.add(f.row().id());
                switch (answer.delivery()) {
                    case DELIVERED -> published.increment();
                    case SKIPPED -> skipped.increment();
                    case CONFLICT -> {
                        conflicts.increment();
                        log.warn("Receiver already had event {} ({}) with different content; counted as delivered",
                                f.row().id(), f.row().eventType());
                    }
                }
            } else if (answer.error() instanceof Rejected) {
                park(f.row(), answer.error(), now);
                parkedNow++;
            } else {
                recordFailure(f.row(), answer.error(), now);
                unreachable |= answer.error() instanceof Unreachable;
                continue; // the rest of this key waits
            }
            if (f.group().size() > wave + 1) {
                continuing.add(f.group()); // its next row may go in the next wave
            }
        }
        markDone(done);
        return new Outcomes(done.size() + parkedNow, continuing, unreachable);
    }

    private List<Row> readBatch(Instant now) {
        return jdbc.sql("""
                SELECT o.id, o.topic, o.message_key, o.event_type, o.envelope::text AS envelope, o.attempts
                FROM outbox o
                WHERE o.published_at IS NULL AND o.parked_at IS NULL
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

    /** Waits for the answer until the chunk's deadline. */
    private static Answer await(CompletableFuture<Delivery> future, long deadlineNanos) {
        try {
            return new Answer(future.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS), null);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            return new Answer(null, cause);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new Answer(null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer(null, e);
        }
    }

    private void markDone(List<UUID> ids) {
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
    }

    private void park(Row row, Throwable error, Instant now) {
        String message = describe(error);
        log.error("Outbox row {} ({} -> {}) rejected by the receiver and parked: {}", row.id(), row.eventType(),
                row.stream(), message);
        jdbc.sql("""
                UPDATE outbox SET parked_at = :now, attempts = attempts + 1, last_error = :error, next_attempt_at = NULL
                WHERE id = :id
                """)
                .param("now", utc(now))
                .param("error", message)
                .param("id", row.id())
                .update();
        parked.increment();
    }

    private void recordFailure(Row row, Throwable error, Instant now) {
        String message = describe(error);
        int attempts = row.attempts() + 1;
        Instant retryAt = now.plus(backoff(attempts));
        log.warn("Outbox send failed for {} ({} -> {}), attempt {}, retry at {}: {}", row.id(), row.eventType(),
                row.stream(), attempts, retryAt, message);
        jdbc.sql("UPDATE outbox SET attempts = :attempts, last_error = :error, next_attempt_at = :retryAt WHERE id = :id")
                .param("attempts", attempts)
                .param("error", message)
                .param("retryAt", utc(retryAt))
                .param("id", row.id())
                .update();
        failed.increment();
    }

    private static String describe(Throwable error) {
        String message = error.getClass().getSimpleName() + ": " + error.getMessage();
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
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

    private record Row(UUID id, String stream, String key, String eventType, String envelope, int attempts) {
        Message message() {
            return new Message(id, stream, key, eventType, envelope);
        }
    }

    private record InFlight(List<Row> group, Row row, CompletableFuture<Delivery> future) {
    }

    private record Answer(Delivery delivery, Throwable error) {
    }

    private record Outcomes(int finished, List<List<Row>> continuing, boolean unreachable) {
    }
}
