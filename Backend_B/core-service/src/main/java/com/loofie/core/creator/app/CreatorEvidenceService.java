package com.loofie.core.creator.app;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.loofie.core.common.Ids;
import com.loofie.core.creator.domain.CreatorExceptions.EvidenceNotFoundException;
import com.loofie.core.creator.domain.CreatorProfile;
import com.loofie.core.creator.domain.Evidence;
import com.loofie.core.creator.infra.CreatorProfileRepository;
import com.loofie.core.creator.infra.EvidenceRepository;
import com.loofie.core.identity.app.RateLimits;
import com.loofie.core.identity.domain.IdentityExceptions.InvalidRequestException;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.storage.Uploads;

/**
 * Screenshots behind her numbers (flow 2) and fast-track level proofs. Upload first (signed URL straight to storage),
 * then attach the key; only a key we issued to her, holding a real file within the limit, is accepted.
 */
@Service
public class CreatorEvidenceService {

    static final int TICKETS_PER_HOUR = 20;
    static final int MAX_LIVE = 10;

    private final Uploads uploads;
    private final EvidenceRepository evidence;
    private final CreatorProfileRepository profiles;
    private final CreatorProfileService profileService;
    private final RateLimits limits;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CreatorEvidenceService(Uploads uploads, EvidenceRepository evidence, CreatorProfileRepository profiles,
                                  CreatorProfileService profileService, RateLimits limits, TransactionTemplate tx,
                                  Clock clock) {
        this.uploads = uploads;
        this.evidence = evidence;
        this.profiles = profiles;
        this.profileService = profileService;
        this.limits = limits;
        this.tx = tx;
        this.clock = clock;
    }

    /** A 5-minute signed URL for one image (jpeg, png, webp or heic, at most 5 MB). */
    public Uploads.UploadTicket uploadTicket(Actor actor, String contentType) {
        UUID me = CreatorProfileService.requireCreator(actor);
        limits.perUser("upload", me, TICKETS_PER_HOUR, Duration.ofHours(1), clock.instant(),
                "Too many uploads. Try again in an hour.");
        return uploads.issue(me, Uploads.Purpose.CREATOR_EVIDENCE, contentType);
    }

    /** Attaches an uploaded screenshot; may complete the passport (it is the last piece of the manual path). */
    public CreatorProfileService.ProfileView attach(Actor actor, String key, Evidence.Kind kind) {
        UUID me = CreatorProfileService.requireCreator(actor);
        if (key == null || key.isBlank() || kind == null) {
            throw new InvalidRequestException("uploadKey and kind are required");
        }
        long size = uploads.verify(key.trim(), me, Uploads.Purpose.CREATOR_EVIDENCE); // asks storage: no transaction
        profiles.insertIfAbsent(me, clock.instant());
        tx.executeWithoutResult(s -> {
            profiles.findForUpdate(me).orElseThrow();
            if (evidence.forCreator(me).stream().filter(Evidence::live).count() >= MAX_LIVE) {
                throw new InvalidRequestException("At most " + MAX_LIVE + " screenshots. Remove one first.");
            }
            if (!uploads.markUsed(key.trim(), size)) {
                throw new Uploads.UploadRefusedException("This upload is already attached.");
            }
            evidence.insert(new Evidence(Ids.newId(), me, key.trim(), kind, Evidence.Status.PENDING, null, null,
                    null, clock.instant()));
            profileService.settle(me, actor, false, true); // nothing new for Backend A; a resubmission for ops
        });
        return profileService.view(me, false);
    }

    /**
     * She takes a screenshot down; the file is deleted from storage. Not the last Insights screenshot of a complete,
     * not-Instagram-verified passport: her numbers would stand on nothing (upload another first).
     */
    public CreatorProfileService.ProfileView remove(Actor actor, UUID evidenceId) {
        UUID me = CreatorProfileService.requireCreator(actor);
        tx.executeWithoutResult(s -> {
            CreatorProfile p = profiles.findForUpdate(me).orElseThrow(() -> new EvidenceNotFoundException(
                    "No such screenshot"));
            Evidence e = evidence.findForUpdate(evidenceId).filter(x -> x.creatorId().equals(me) && x.live())
                    .orElseThrow(() -> new EvidenceNotFoundException("No such screenshot"));
            if (e.kind() == Evidence.Kind.INSIGHTS && p.completed() && !p.instagramVerified()
                    && evidence.live(me, Evidence.Kind.INSIGHTS) <= 1) {
                throw new InvalidRequestException("This screenshot supports your numbers. Upload a newer one first, "
                        + "then remove this one.");
            }
            evidence.review(evidenceId, Evidence.Status.REMOVED, null, me, clock.instant());
            uploads.requestDelete(e.uploadKey());
        });
        return profileService.view(me, false);
    }
}
