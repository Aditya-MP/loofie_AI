package com.loofie.core.draft.app;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.MeterRegistry;

import com.loofie.core.common.ServiceBusyException;

/**
 * F7 screens read in one read-only transaction (one connection, taken once, instead of one per query) behind a
 * fence: at most {@code loofie.queries.draft-max-concurrent} at a time, waiting at most {@code queue-wait} for a slot,
 * then a quick 503. Measured: without it, a cold app at 100 rps queued 200 requests on the 20-connection pool, each
 * request taking a connection seven times, and the backlog fed itself (p50 2 s, 503s and 500s).
 */
@Component
class DraftReadFence {

    private final Semaphore permits;
    private final TransactionTemplate readTx;
    private final Duration queueWait;
    private final MeterRegistry meters;

    DraftReadFence(PlatformTransactionManager txManager, MeterRegistry meters,
                   @Value("${loofie.queries.draft-max-concurrent:12}") int max,
                   @Value("${loofie.queries.queue-wait:1s}") Duration queueWait) {
        this.permits = new Semaphore(max, true);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.queueWait = queueWait;
        this.meters = meters;
    }

    <T> T read(Supplier<T> read) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(queueWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceBusyException("Interrupted while waiting for a read slot");
        }
        if (!acquired) {
            meters.counter("loofie.queries.rejected", "screen", "draft").increment();
            throw new ServiceBusyException("Too many video screens open at once; retry in a moment");
        }
        try {
            return readTx.execute(s -> read.get());
        } finally {
            permits.release();
        }
    }
}
