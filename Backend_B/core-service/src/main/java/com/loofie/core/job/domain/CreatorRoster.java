package com.loofie.core.job.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the job engine needs from creator profiles for F9 (implemented by the creator package): who can be offered as
 * a replacement, what the brand may see of them, and the level drop after a missed production deadline.
 */
public interface CreatorRoster {

    /** What a brand sees of an offered creator: her public profile, never contact details. */
    record Card(UUID creatorId, String displayName, String instagramHandle, String city, String level,
                List<String> categories, Integer ratePerReelRupees, Boolean partnershipAds, List<String> sampleLinks) {
    }

    /** The creator's public card, only while she can be booked (complete, listed, account active). */
    Optional<Card> bookableCard(UUID creatorId);

    /**
     * Foundation v2 F9: a creator who misses the production deadline "is moved back to NEW level". Runs in the
     * caller's transaction; nothing happens if she is NEW already.
     */
    void missedProductionDeadline(UUID creatorId, UUID jobId, Instant now);
}
