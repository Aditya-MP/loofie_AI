package com.loofie.core.job.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.storage.Uploads;

/**
 * Transitions requested over HTTP (people, not timers). F6 photos and videos (receipt, handover, damage) must be files
 * issued by the upload register to the person acting (F7 D8): checked with the store first, outside any transaction
 * (it is an HTTP call), then claimed in the same transaction as the state change, so a key can be attached once only.
 */
@Service
public class JobActions {

    private static final Map<ActorRole, Set<Uploads.Purpose>> MAY_UPLOAD = Map.of(
            ActorRole.CREATOR, Set.of(Uploads.Purpose.RECEIPT_PHOTO, Uploads.Purpose.DAMAGE_EVIDENCE),
            ActorRole.BRAND_ADMIN, Set.of(Uploads.Purpose.HANDOVER_PHOTO),
            ActorRole.BRAND_MEMBER, Set.of(Uploads.Purpose.HANDOVER_PHOTO),
            ActorRole.OPS, Set.of(Uploads.Purpose.RECEIPT_PHOTO, Uploads.Purpose.HANDOVER_PHOTO,
                    Uploads.Purpose.DAMAGE_EVIDENCE));

    private final JobTransitionService engine;
    private final JobFacts jobs;
    private final Uploads uploads;
    private final TransactionTemplate tx;

    JobActions(JobTransitionService engine, JobFacts jobs, Uploads uploads, TransactionTemplate tx) {
        this.engine = engine;
        this.jobs = jobs;
        this.uploads = uploads;
        this.tx = tx;
    }

    /** An upload link for a job photo (receipt, handover, damage); only the roles that use each kind get one. */
    public Uploads.UploadTicket ticket(Actor actor, UUID jobId, Uploads.Purpose purpose, String contentType) {
        if (purpose == null || !MAY_UPLOAD.getOrDefault(actor.role().acting(), Set.of()).contains(purpose)) {
            throw new ActorNotAllowedException(actor.role() + " may not upload " + purpose);
        }
        jobs.forActor(actor, jobId); // someone outside the job learns nothing about it
        return uploads.issue(actor.userId(), purpose, contentType);
    }

    public TransitionResult run(TransitionCommand cmd) {
        Map<String, Uploads.Purpose> files = files(cmd);
        if (files.isEmpty()) {
            return engine.transition(cmd);
        }
        Map<String, Long> sizes = new LinkedHashMap<>();
        files.forEach((key, purpose) -> sizes.put(key, uploads.verify(key, cmd.actor().userId(), purpose)));
        return tx.execute(s -> {
            sizes.forEach((key, size) -> {
                if (!uploads.markUsed(key, size)) {
                    throw new Uploads.UploadRefusedException("This upload is already attached.");
                }
            });
            return engine.transition(cmd);
        });
    }

    /** The files this action attaches, by kind. Fields an action does not use are ignored, as the engine does. */
    private static Map<String, Uploads.Purpose> files(TransitionCommand cmd) {
        TransitionDetails d = cmd.details();
        List<Object[]> out = new ArrayList<>();
        switch (cmd.action()) {
            case CONFIRM_RECEIVED -> out.add(new Object[] {d.photoRef(), Uploads.Purpose.RECEIPT_PHOTO});
            case MARK_SHIPPED, RESHIP -> out.add(new Object[] {d.handoverPhotoRef(), Uploads.Purpose.HANDOVER_PHOTO});
            case REPORT_DELIVERY_ISSUE -> out.add(new Object[] {d.evidenceRef(), Uploads.Purpose.DAMAGE_EVIDENCE});
            default -> {
                // no files
            }
        }
        Map<String, Uploads.Purpose> files = new LinkedHashMap<>();
        for (Object[] f : out) {
            if (f[0] != null && !((String) f[0]).isBlank()) {
                files.put(((String) f[0]).trim(), (Uploads.Purpose) f[1]);
            }
        }
        return files;
    }
}
