package com.loofie.core.draft.app;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.loofie.core.draft.infra.DraftRepository;
import com.loofie.core.job.app.JobFacts;
import com.loofie.core.storage.Uploads;

/**
 * D9: videos are not kept for ever. Approved: the ad-rights period plus 30 days. Failed or never delivered: 7 days.
 * Delivered but never approved: 180 days after upload, once the job has ended (a refund or cancellation), so a
 * dispute still has its evidence. Files are queued on the upload register; {@code UploadCleanup} removes them.
 */
@Component
public class DraftRetention {

    public record Result(int purged, int scheduled) {
    }

    static final int BATCH = 200;

    private final DraftRepository drafts;
    private final JobFacts jobs;
    private final Uploads uploads;
    private final TransactionTemplate tx;
    private final Clock clock;

    DraftRetention(DraftRepository drafts, JobFacts jobs, Uploads uploads, TransactionTemplate tx, Clock clock) {
        this.drafts = drafts;
        this.jobs = jobs;
        this.uploads = uploads;
        this.tx = tx;
        this.clock = clock;
    }

    public Result runOnce() {
        Instant now = clock.instant();
        int scheduled = 0;
        for (UUID jobId : drafts.jobsWithoutRetention(now.minus(DraftRules.UNAPPROVED_KEEP), BATCH)) {
            boolean ended = jobs.find(jobId).map(f -> f.status().isTerminal()).orElse(true);
            if (ended) {
                tx.executeWithoutResult(s -> drafts.setRetention(jobId, now, now));
                scheduled++;
            }
        }
        List<DraftRepository.Purge> due = drafts.dueForPurge(now, BATCH);
        for (DraftRepository.Purge p : due) {
            tx.executeWithoutResult(s -> {
                uploads.requestDelete(p.masterKey());
                if (p.previewKey() != null) {
                    uploads.requestDelete(p.previewKey());
                }
                drafts.markPurged(p.id(), now);
            });
        }
        return new Result(due.size(), scheduled);
    }
}
