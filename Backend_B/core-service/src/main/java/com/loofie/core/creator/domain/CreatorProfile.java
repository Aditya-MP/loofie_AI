package com.loofie.core.creator.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A creator's passport (Foundation v2 feature 1): what brands and F3 filters see. Numbers are kept apart
 * ({@link MetricValue}) because each carries its own source and date.
 */
public record CreatorProfile(UUID creatorId, String displayName, String city, String state, List<String> languages,
                             List<String> categories, List<Format> formats, Integer ratePerReelRupees,
                             Boolean partnershipAds, Integer turnaroundDays, String bio, List<String> sampleLinks,
                             String instagramHandle, InstagramStatus instagramStatus, Boolean instagramInsightsAllowed,
                             Instant instagramCheckedAt, Level level, String levelReason,
                             MarketplaceStatus marketplaceStatus, String marketplaceReason, Instant completedAt,
                             Instant createdAt, Instant updatedAt, long version) {

    public enum Format { REEL, STATIC, STORY, CAROUSEL }

    /** NEW: up to ₹5,000 per job. VERIFIED: two on-time jobs, or three collaboration proofs checked by ops. */
    public enum Level { NEW, VERIFIED }

    /** Separate from account status (product brief 14.5): HIDDEN = she paused herself; BLOCKED = ops, with a reason. */
    public enum MarketplaceStatus { LISTED, HIDDEN, BLOCKED }

    /** As last read from Backend A. NOT_CONNECTED until she starts Instagram connect. */
    public enum InstagramStatus {
        NOT_CONNECTED, SYNCING, ACTIVE, ERROR, EXPIRED, REVOKED;

        /** Backend A's status word, or null when it is not one we know. */
        public static InstagramStatus fromBackendA(String s) {
            if (s == null) {
                return null;
            }
            return switch (s) {
                case "syncing" -> SYNCING;
                case "active" -> ACTIVE;
                case "error" -> ERROR;
                case "expired" -> EXPIRED;
                case "revoked" -> REVOKED;
                default -> null;
            };
        }
    }

    public static CreatorProfile empty(UUID creatorId, Instant now) {
        return new CreatorProfile(creatorId, null, null, null, List.of(), List.of(), List.of(), null, null, null, null,
                List.of(), null, InstagramStatus.NOT_CONNECTED, null, null, Level.NEW, null,
                MarketplaceStatus.LISTED, null, null, now, now, 0);
    }

    public boolean completed() {
        return completedAt != null;
    }

    /** Instagram numbers are verified by Backend A right now (not just "was once connected"). */
    public boolean instagramVerified() {
        return instagramStatus == InstagramStatus.ACTIVE;
    }

    public CreatorProfile withForm(CreatorForm f, Instant now) {
        return new CreatorProfile(creatorId, f.displayName(), f.city(), f.state(), f.languages(), f.categories(),
                f.formats(), f.ratePerReelRupees(), f.partnershipAds(), f.turnaroundDays(), f.bio(), f.sampleLinks(),
                instagramVerified() ? instagramHandle : f.instagramHandle(), instagramStatus,
                instagramInsightsAllowed, instagramCheckedAt, level, levelReason, marketplaceStatus,
                marketplaceReason, completedAt, createdAt, now, version);
    }
}
