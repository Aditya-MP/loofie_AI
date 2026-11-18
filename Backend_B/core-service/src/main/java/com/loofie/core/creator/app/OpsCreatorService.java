package com.loofie.core.creator.app;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.creator.domain.CreatorExceptions.CreatorNotFoundException;
import com.loofie.core.creator.domain.CreatorExceptions.EvidenceNotFoundException;
import com.loofie.core.creator.domain.CreatorProfile;
import com.loofie.core.creator.domain.CreatorProfile.Level;
import com.loofie.core.creator.domain.CreatorProfile.MarketplaceStatus;
import com.loofie.core.creator.domain.Evidence;
import com.loofie.core.creator.domain.MetricValue;
import com.loofie.core.creator.infra.CreatorProfileRepository;
import com.loofie.core.creator.infra.EvidenceRepository;
import com.loofie.core.identity.app.AuditLog;
import com.loofie.core.identity.domain.IdentityExceptions.InvalidRequestException;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.storage.Uploads;

/**
 * The ops side of the passport (Foundation v2 feature 10, "creator verification queue"): look at screenshots, confirm
 * numbers (OPS_CHECKED), set the level, block a profile. Every look and every change is audited (DECISIONS section 10:
 * viewing creator data is logged). Ops never auto-rejects on Backend A's quality label; it decides.
 */
@Service
public class OpsCreatorService {

    public record QueueItem(UUID evidenceId, UUID creatorId, String displayName, String instagramHandle,
                            Evidence.Kind kind, Instant uploadedAt) {
    }

    public record EvidenceLink(UUID evidenceId, URI url, Instant expiresAt) {
    }

    private final CreatorProfileRepository profiles;
    private final EvidenceRepository evidence;
    private final CreatorProfileService profileService;
    private final Uploads uploads;
    private final AuditLog audit;
    private final Clock clock;

    public OpsCreatorService(CreatorProfileRepository profiles, EvidenceRepository evidence,
                             CreatorProfileService profileService, Uploads uploads, AuditLog audit, Clock clock) {
        this.profiles = profiles;
        this.evidence = evidence;
        this.profileService = profileService;
        this.uploads = uploads;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public CreatorProfileService.ProfileView view(Actor actor, UUID creatorId) {
        requireOps(actor);
        CreatorProfileService.ProfileView v = profileService.view(require(creatorId).creatorId(), true);
        audit.record(actor, AuditLog.CREATOR_VIEWED, creatorId, null, Map.of());
        return v;
    }

    /** Screenshots waiting for a look, oldest first. */
    public List<QueueItem> queue(Actor actor, Instant after, int limit) {
        requireOps(actor);
        if (limit < 1 || limit > 200) {
            throw new InvalidRequestException("limit must be 1 to 200");
        }
        return evidence.pending(after == null ? Instant.EPOCH : after, limit).stream().map(e -> {
            CreatorProfile p = profiles.find(e.creatorId()).orElseThrow();
            return new QueueItem(e.id(), e.creatorId(), p.displayName(), p.instagramHandle(), e.kind(),
                    e.createdAt());
        }).toList();
    }

    /** A 5-minute link to look at one screenshot; the look is audited. */
    @Transactional
    public EvidenceLink open(Actor actor, UUID evidenceId) {
        requireOps(actor);
        Evidence e = evidence.find(evidenceId).filter(x -> x.status() != Evidence.Status.REMOVED)
                .orElseThrow(() -> new EvidenceNotFoundException("No such screenshot"));
        URI url = uploads.viewUrl(e.uploadKey());
        audit.record(actor, AuditLog.EVIDENCE_VIEWED, e.creatorId(), null, Map.of("evidenceId", evidenceId.toString()));
        return new EvidenceLink(evidenceId, url, clock.instant().plus(Uploads.VIEW_TTL));
    }

    /** Accept or reject a screenshot; a rejection needs a note she will see. */
    @Transactional
    public void review(Actor actor, UUID evidenceId, boolean accept, String note) {
        requireOps(actor);
        String n = note == null || note.isBlank() ? null : note.trim();
        if (!accept && n == null) {
            throw new InvalidRequestException("Say why the screenshot is rejected; the creator sees it");
        }
        if (n != null && n.length() > 300) {
            throw new InvalidRequestException("Note: at most 300 characters");
        }
        Evidence e = evidence.findForUpdate(evidenceId).orElseThrow(() -> new EvidenceNotFoundException(
                "No such screenshot"));
        if (e.status() != Evidence.Status.PENDING) {
            throw new InvalidRequestException("This screenshot was already " + e.status().name().toLowerCase());
        }
        evidence.review(evidenceId, accept ? Evidence.Status.ACCEPTED : Evidence.Status.REJECTED, n, actor.userId(),
                clock.instant());
        audit.record(actor, accept ? AuditLog.EVIDENCE_ACCEPTED : AuditLog.EVIDENCE_REJECTED, e.creatorId(), n,
                Map.of("evidenceId", evidenceId.toString(), "kind", e.kind().name()));
    }

    /** Ops confirms a number from a screenshot (or corrects it): stored as OPS_CHECKED next to what she typed. */
    @Transactional
    public CreatorProfileService.ProfileView checkMetric(Actor actor, UUID creatorId, MetricValue.Metric metric,
                                                         Long value, String reason) {
        requireOps(actor);
        String why = requireReason(reason);
        if (metric == null || value == null || value < 0 || value > 1_000_000_000L) {
            throw new InvalidRequestException("metric and a value from 0 are required");
        }
        CreatorProfile p = lock(creatorId);
        Instant now = clock.instant();
        profiles.upsertMetric(creatorId, new MetricValue(metric, value, MetricValue.Source.OPS_CHECKED,
                LocalDate.ofInstant(now, CreatorProfileService.INDIA)), actor.userId(), now);
        audit.record(actor, AuditLog.METRIC_CHECKED, creatorId, why, Map.of("metric", metric.name(), "value", value));
        profileService.settle(p.creatorId(), actor, true);
        return profileService.view(creatorId, false);
    }

    /** VERIFIED: two on-time jobs, or three collaboration proofs (decision C5). Always with a reason. */
    @Transactional
    public CreatorProfileService.ProfileView setLevel(Actor actor, UUID creatorId, Level level, String reason) {
        requireOps(actor);
        String why = requireReason(reason);
        if (level == null) {
            throw new InvalidRequestException("level is required (NEW or VERIFIED)");
        }
        CreatorProfile p = lock(creatorId);
        profiles.setLevel(creatorId, level, why, clock.instant());
        audit.record(actor, AuditLog.LEVEL_CHANGED, creatorId, why, Map.of("from", p.level().name(), "to",
                level.name()));
        profileService.settle(creatorId, actor, true);
        return profileService.view(creatorId, false);
    }

    /** Takes her off the marketplace (she cannot be shortlisted or accept work) or puts her back. */
    @Transactional
    public CreatorProfileService.ProfileView block(Actor actor, UUID creatorId, boolean blocked, String reason) {
        requireOps(actor);
        String why = requireReason(reason);
        CreatorProfile p = lock(creatorId);
        if (blocked == (p.marketplaceStatus() == MarketplaceStatus.BLOCKED)) {
            throw new InvalidRequestException(blocked ? "Already blocked" : "Not blocked");
        }
        profiles.setMarketplace(creatorId, blocked ? MarketplaceStatus.BLOCKED : MarketplaceStatus.LISTED,
                blocked ? why : null, clock.instant());
        audit.record(actor, blocked ? AuditLog.MARKETPLACE_BLOCKED : AuditLog.MARKETPLACE_UNBLOCKED, creatorId, why,
                Map.of());
        return profileService.view(creatorId, false);
    }

    private CreatorProfile require(UUID creatorId) {
        return profiles.find(creatorId).orElseThrow(() -> new CreatorNotFoundException("No such creator"));
    }

    private CreatorProfile lock(UUID creatorId) {
        return profiles.findForUpdate(creatorId).orElseThrow(() -> new CreatorNotFoundException("No such creator"));
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new InvalidRequestException("A reason is required; it is kept in the audit log");
        }
        if (reason.trim().length() > 300) {
            throw new InvalidRequestException("Reason: at most 300 characters");
        }
        return reason.trim();
    }

    static void requireOps(Actor actor) {
        if (!actor.role().isOps()) {
            throw new ActorNotAllowedException("Only Loofie staff can do this");
        }
    }
}
