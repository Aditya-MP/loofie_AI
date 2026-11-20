package com.loofie.core.common;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Screens (F7 videos, F9 swap offers and disputes) read in one read-only transaction (one connection, taken once,
 * instead of one per query) behind one shared fence: at most {@code loofie.queries.screen-max-concurrent} at a time,
 * waiting at most {@code queue-wait} for a slot, then a quick 503. Measured in F7: without it, a cold app at 100 rps
 * queued 200 requests on the 20-connection pool, each taking a connection seven times, and the backlog fed itself.
 * One fence for all screens (F9 added the swap and dispute views), so together they never take the whole pool.
 */
@Component
public class ScreenReadFence {

    private final Semaphore permits;
    private final TransactionTemplate readTx;
    private final Duration queueWait;
    private final MeterRegistry meters;

    ScreenReadFence(PlatformTransactionManager txManager, MeterRegistry meters,
                    @Value("${loofie.queries.screen-max-concurrent:${loofie.queries.draft-max-concurrent:12}}") int max,
                    @Value("${loofie.queries.queue-wait:1s}") Duration queueWait) {
        this.permits = new Semaphore(max, true);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.queueWait = queueWait;
        this.meters = meters;
    }

    /** @param screen a short name for the metric of refused reads ({@code loofie.queries.rejected}) */
    public <T> T read(String screen, Supplier<T> read) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(queueWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceBusyException("Interrupted while waiting for a read slot");
        }
        if (!acquired) {
            meters.counter("loofie.queries.rejected", "screen", screen).increment();
            throw new ServiceBusyException("Too many screens open at once; retry in a moment");
        }
        try {
            return readTx.execute(s -> read.get());
        } finally {
            permits.release();
        }
    }
}
