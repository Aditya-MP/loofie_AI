package com.loofie.core.draft.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import com.loofie.core.common.Ids;
import com.loofie.core.draft.domain.Brief;
import com.loofie.core.draft.domain.DraftStatus;
import com.loofie.core.draft.domain.VideoChecks;
import com.loofie.core.draft.infra.BriefRepository;
import com.loofie.core.draft.infra.DraftRepository;
import com.loofie.core.job.app.JobFacts;
import com.loofie.core.job.app.JobTransitionService;
import com.loofie.core.job.app.TransitionCommand;
import com.loofie.core.job.app.TransitionDetails;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobExceptions;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.media.MediaTools;
import com.loofie.core.notification.app.NotificationService;
import com.loofie.core.storage.ObjectStore;
import com.loofie.core.storage.Uploads;

/**
 * Works one uploaded video (never on a request thread, never with a database connection held while files move):
 * <ol>
 *   <li>download it, ffprobe it, apply the checks; a failure is the creator's to fix and the brand never hears of it;</li>
 *   <li>make the watermarked 720p preview and store it;</li>
 *   <li>in one transaction: SUBMIT_DRAFT (or RESUBMIT) as the creator, PREVIEW_READY, the draft READY, the brand told.
 *       The brand's review clock starts here, so processing time is never taken from the brand, and "on time" is
 *       judged by the upload time, so it is never taken from the creator either.</li>
 * </ol>
 * Our own trouble (storage down, tools missing, time limit) is retried with back-off and never fails the creator's
 * video by itself; after 3 tries ops is told once. A video the tools refuse is failed with a plain explanation.
 */
@Component
public class DraftProcessor {

    private static final Logger log = LoggerFactory.getLogger(DraftProcessor.class);
    static final int OPS_ALERT_AFTER_TRIES = 3;
    /** A preview that times out this often is the file's problem (e.g. a broken export), not ours. */
    static final int MAX_TRANSCODE_TIMEOUTS = 3;

    private final DraftRepository drafts;
    private final BriefRepository briefs;
    private final JobFacts jobs;
    private final JobTransitionService engine;
    private final MediaTools media;
    private final ObjectStore store;
    private final Uploads uploads;
    private final NotificationService notifications;
    private final DraftMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;

    DraftProcessor(DraftRepository drafts, BriefRepository briefs, JobFacts jobs, JobTransitionService engine,
                   MediaTools media, ObjectStore store, Uploads uploads, NotificationService notifications,
                   DraftMapper mapper, TransactionTemplate tx, Clock clock, MeterRegistry meters) {
        this.drafts = drafts;
        this.briefs = briefs;
        this.jobs = jobs;
        this.engine = engine;
        this.media = media;
        this.store = store;
        this.uploads = uploads;
        this.notifications = notifications;
        this.mapper = mapper;
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
    }

    /** What happened to one video in this run. */
    public enum Outcome { READY, FAILED, SUPERSEDED, RETRY }

    public Outcome process(DraftRepository.Draft d) {
        Timer.Sample sample = Timer.start(meters);
        Outcome outcome = run(d);
        sample.stop(meters.timer("loofie.drafts.processing", "outcome", outcome.name()));
        return outcome;
    }

    private Outcome run(DraftRepository.Draft d) {
        JobFacts.Facts job = jobs.find(d.jobId()).orElse(null);
        if (job == null || !DraftRules.UPLOAD_STATES.contains(job.status())
                || !d.creatorId().equals(job.activeCreatorId())) {
            return supersede(d, "The job moved on before the video was checked ("
                    + (job == null ? "missing" : job.status()) + ")");
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("loofie-draft-");
            Path master = dir.resolve("master" + ext(d.masterKey()));
            store.download(d.masterKey(), master);
            Brief brief = briefs.find(d.jobId()).map(BriefRepository.Stored::brief).orElseGet(Brief::defaults);

            // 1. Checks.
            VideoChecks.Video video;
            DraftViews.Probe probe = null;
            try {
                MediaTools.MediaInfo info = media.probe(master);
                video = new VideoChecks.Video(true, info.hasVideo(), info.width(), info.height(), info.seconds(),
                        info.hasAudio());
                probe = new DraftViews.Probe(info.width(), info.height(), round(info.seconds()), info.hasAudio());
            } catch (MediaTools.MediaFileException e) {
                video = VideoChecks.Video.unreadable();
            }
            List<VideoChecks.Failure> failures = VideoChecks.check(video, brief);
            if (!failures.isEmpty()) {
                return fail(d, failures, probe);
            }
            if (d.status() == DraftStatus.CHECKING) {
                String probeJson = mapper.probeJson(probe);
                tx.executeWithoutResult(s -> drafts.markPreviewing(d.id(), probeJson, clock.instant()));
            }

            // 2. Preview. The key is recorded first, so a retry after a crash overwrites the same file.
            String previewKey = d.previewKey() != null ? d.previewKey()
                    : Uploads.newKey(Uploads.Purpose.DRAFT_PREVIEW, d.creatorId(), "mp4");
            Path preview = dir.resolve("preview.mp4");
            try {
                media.preview(master, preview, d.jobId().toString().replace("-", "").substring(0, 8),
                        brief.maxSeconds() + 1);
            } catch (MediaTools.MediaFileException e) {
                return fail(d, List.of(new VideoChecks.Failure("PROCESSING_FAILED", "This video could not be "
                        + "converted for the brand. Export it again as MP4 (H.264) and upload it.")), probe);
            }
            long previewBytes = Files.size(preview);
            if (d.previewKey() == null) {
                tx.executeWithoutResult(s -> {
                    uploads.registerMade(previewKey, d.creatorId(), Uploads.Purpose.DRAFT_PREVIEW, "video/mp4",
                            previewBytes);
                    drafts.setPreview(d.id(), previewKey, clock.instant());
                });
            }
            store.upload(previewKey, preview, "video/mp4");

            // 3. Deliver.
            return deliver(d);
        } catch (ObjectStore.StoreUnavailableException e) {
            return retry(d, "storage: " + e.getMessage());
        } catch (MediaTools.MediaUnavailableException e) {
            if (e.timedOut() && d.status() == DraftStatus.PREVIEWING && d.tries() >= MAX_TRANSCODE_TIMEOUTS) {
                return fail(d, List.of(new VideoChecks.Failure("PROCESSING_FAILED", "This video could not be "
                        + "converted for the brand. Export it again as MP4 (H.264) and upload it.")), null);
            }
            return retry(d, "media tools: " + e.getMessage());
        } catch (IOException e) {
            return retry(d, "local files: " + e);
        } finally {
            deleteTree(dir);
        }
    }

    private Outcome deliver(DraftRepository.Draft d) {
        try {
            tx.executeWithoutResult(s -> {
                JobFacts.Facts job = jobs.find(d.jobId()).orElseThrow(
                        () -> new JobExceptions.JobNotFoundException(d.jobId()));
                JobAction submit = job.status() == JobStatus.REVISION_REQUESTED ? JobAction.RESUBMIT
                        : JobAction.SUBMIT_DRAFT;
                UUID correlation = Ids.newId();
                TransitionDetails details = TransitionDetails.draft(d.id());
                engine.transition(new TransitionCommand(d.jobId(), submit, Actor.creator(d.creatorId()), null, null,
                        correlation, details, null));
                engine.transition(new TransitionCommand(d.jobId(), JobAction.PREVIEW_READY, Actor.system(), null, null,
                        correlation, details, null));
                Instant now = clock.instant();
                drafts.markReady(d.id(), d.jobId(), now);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("draftId", d.id().toString());
                payload.put("attempt", d.attempt());
                payload.put("revision", submit == JobAction.RESUBMIT);
                notifications.toBrand(job.brandOrgId(), "DRAFT_READY_FOR_REVIEW", d.jobId(), payload, now);
                notifications.toCreator(d.creatorId(), "DRAFT_SENT_TO_BRAND", d.jobId(), payload, now);
            });
            return Outcome.READY;
        } catch (ObjectOptimisticLockingFailureException e) {
            return retry(d, "the job changed at the same moment; trying again");
        } catch (JobExceptions.IllegalTransitionException | JobExceptions.JobNotFoundException
                 | JobExceptions.TransitionGuardException | JobExceptions.ActorNotAllowedException e) {
            return supersede(d, e.getMessage());
        }
    }

    private Outcome fail(DraftRepository.Draft d, List<VideoChecks.Failure> failures, DraftViews.Probe probe) {
        Instant now = clock.instant();
        tx.executeWithoutResult(s -> {
            drafts.markFailed(d.id(), mapper.failuresJson(failures), mapper.probeJson(probe), now,
                    now.plus(DraftRules.FAILED_KEEP));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("draftId", d.id().toString());
            payload.put("codes", failures.stream().map(VideoChecks.Failure::code).toList());
            notifications.toCreator(d.creatorId(), "DRAFT_CHECKS_FAILED", d.jobId(), payload, now);
        });
        meters.counter("loofie.drafts.failed", "code", failures.getFirst().code()).increment();
        return Outcome.FAILED;
    }

    private Outcome supersede(DraftRepository.Draft d, String why) {
        Instant now = clock.instant();
        // Never shown to the brand: kept as briefly as a failed video.
        tx.executeWithoutResult(s -> drafts.markSuperseded(d.id(), why, now, now.plus(DraftRules.FAILED_KEEP)));
        log.info("Draft {} of job {} not delivered: {}", d.id(), d.jobId(), why);
        return Outcome.SUPERSEDED;
    }

    /** 30 s, 2 min, 10 min, then every 30 min. Ops hears about it once, after the third try. */
    private Outcome retry(DraftRepository.Draft d, String error) {
        Instant now = clock.instant();
        Duration wait = switch (d.tries()) {
            case 0, 1 -> Duration.ofSeconds(30);
            case 2 -> Duration.ofMinutes(2);
            case 3 -> Duration.ofMinutes(10);
            default -> Duration.ofMinutes(30);
        };
        log.warn("Draft {} of job {} retried in {} (try {}): {}", d.id(), d.jobId(), wait, d.tries(), error);
        tx.executeWithoutResult(s -> drafts.retryLater(d.id(), now.plus(wait), error, now));
        if (d.tries() >= OPS_ALERT_AFTER_TRIES && !d.opsAlerted()) {
            // One standing alarm a day with the size of the problem, not one per video: a storage outage can leave
            // hundreds waiting, and ops needs the one message that says "processing is stuck".
            DraftRepository.Backlog backlog = drafts.backlog();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("waiting", backlog.waiting());
            payload.put("oldestSubmittedAt", String.valueOf(backlog.oldestSubmittedAt()));
            payload.put("jobId", d.jobId().toString());
            payload.put("draftId", d.id().toString());
            payload.put("tries", d.tries());
            payload.put("error", error.length() > 200 ? error.substring(0, 200) : error);
            notifications.toOpsOncePerDay("DRAFT_PROCESSING_STUCK", payload, now);
            tx.executeWithoutResult(s -> drafts.markOpsAlerted(d.id()));
        }
        return Outcome.RETRY;
    }

    private static String ext(String key) {
        int dot = key.lastIndexOf('.');
        return dot < 0 ? "" : key.substring(dot);
    }

    private static double round(double s) {
        return Math.round(s * 10) / 10.0;
    }

    private static void deleteTree(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // temp folder; the OS cleans it
                }
            });
        } catch (IOException ignored) {
            // nothing to clean
        }
    }
}
