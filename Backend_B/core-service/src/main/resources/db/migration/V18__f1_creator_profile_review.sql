-- F1 round C, agreed 2026-11-18 (replaces decision C4): a manual-path creator is not offered to brands until ops
-- approves her profile ("profile approved", distinct from the NEW / VERIFIED level). Instagram-verified profiles are
-- approved at once. Ops promise: 24-48 hours; the SLA watch tells ops at 24 h and the founder at 48 h.
ALTER TABLE creator_profiles
    ADD COLUMN review_status        varchar(18) NOT NULL DEFAULT 'NONE'
        CHECK (review_status IN ('NONE', 'PENDING', 'APPROVED', 'CHANGES_REQUESTED')),
    ADD COLUMN review_note          varchar(300),
    ADD COLUMN reviewed_by          uuid,
    ADD COLUMN reviewed_at          timestamptz,
    ADD COLUMN review_requested_at  timestamptz,
    ADD COLUMN review_alerted_at    timestamptz,   -- ops told: waiting over 24 h
    ADD COLUMN review_escalated_at  timestamptz,   -- founder told: waiting over 48 h
    ADD CONSTRAINT creator_review_note_check CHECK (review_status <> 'CHANGES_REQUESTED' OR review_note IS NOT NULL);

-- Profiles completed before this rule: Instagram-verified ones are approved, the rest wait for ops like new ones.
UPDATE creator_profiles SET review_status = 'APPROVED', reviewed_at = completed_at
WHERE completed_at IS NOT NULL AND instagram_status = 'ACTIVE';
UPDATE creator_profiles SET review_status = 'PENDING', review_requested_at = completed_at
WHERE completed_at IS NOT NULL AND instagram_status <> 'ACTIVE';

-- The ops review queue and the SLA watch: oldest waiting first.
CREATE INDEX creator_review_pending_idx ON creator_profiles (review_requested_at) WHERE review_status = 'PENDING';
-- Bookable now also needs APPROVED (F3 filters read this index).
DROP INDEX creator_profiles_bookable_idx;
CREATE INDEX creator_profiles_bookable_idx ON creator_profiles (level, rate_per_reel_rupees)
    WHERE completed_at IS NOT NULL AND marketplace_status = 'LISTED' AND review_status = 'APPROVED';
