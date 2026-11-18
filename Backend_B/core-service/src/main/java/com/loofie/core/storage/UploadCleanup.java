package com.loofie.core.storage;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.common.LeaderLease;
import com.loofie.core.identity.app.PersonalDataHolder;

/**
 * Removes files from storage that must not stay: screenshots taken down, every file of a deleted account, and uploads
 * that were issued but never attached (an hour after their ticket expired). The store is called with no database
 * transaction open; each file is marked deleted only after the store confirmed it, so a failure is simply retried on
 * the next run.
 */
@Component
public class UploadCleanup {

    private static final Logger log = LoggerFactory.getLogger(UploadCleanup.class);
    static final int BATCH = 200;
    static final Duration UNUSED_GRACE = Duration.ofHours(1);

    public record Result(int removed, int failed) {
    }

    private final Uploads uploads;
    private final ObjectStore store;
    private final Clock clock;

    public UploadCleanup(Uploads uploads, ObjectStore store, Clock clock) {
        this.uploads = uploads;
        this.store = store;
        this.clock = clock;
    }

    public Result runOnce() {
        if (!store.ready()) {
            return new Result(0, 0); // nothing can have been uploaded
        }
        List<String> keys = uploads.dueForRemoval(clock.instant().minus(UNUSED_GRACE), BATCH);
        int removed = 0;
        for (String key : keys) {
            try {
                store.delete(key);
            } catch (ObjectStore.StoreUnavailableException e) {
                log.warn("Upload cleanup stopped, storage unavailable: {}", e.getMessage());
                return new Result(removed, keys.size() - removed);
            }
            uploads.markDeleted(key);
            removed++;
        }
        if (removed > 0) {
            log.info("Upload cleanup: removed {} files from storage", removed);
        }
        return new Result(removed, 0);
    }

    /** Account deletion: every file she uploaded is queued (removed by {@link #runOnce}). */
    @Component
    static class Erasure implements PersonalDataHolder {

        private final Uploads uploads;

        Erasure(Uploads uploads) {
            this.uploads = uploads;
        }

        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public void erase(UUID userId) {
            uploads.requestDeleteAll(userId);
        }
    }

    /** Runs on the instance holding the "upload-cleanup" lease. */
    @Component
    @ConditionalOnProperty(name = "loofie.storage.cleanup-enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduler {

        private final UploadCleanup cleanup;
        private final LeaderLease lease;

        Scheduler(UploadCleanup cleanup, LeaderLease lease) {
            this.cleanup = cleanup;
            this.lease = lease;
        }

        @Scheduled(fixedDelayString = "${loofie.storage.cleanup-interval:10m}", initialDelayString = "2m")
        void run() {
            try {
                if (lease.tryAcquire("upload-cleanup", Duration.ofMinutes(5))) {
                    cleanup.runOnce();
                }
            } catch (RuntimeException e) {
                log.error("Upload cleanup failed", e);
            }
        }
    }
}
