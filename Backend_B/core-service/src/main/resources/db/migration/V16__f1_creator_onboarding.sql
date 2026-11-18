-- F1 round C part 1: creator passport, Instagram connection state, self-reported numbers, uploads, ops evidence
-- review (docs/features/f1-identity-round-c.md). API_VERIFIED numbers stay in Backend A (decision C2): only numbers we
-- collect (SELF_REPORTED) or ops confirms (OPS_CHECKED) are stored here.

CREATE TABLE creator_profiles (
    user_id                    uuid         PRIMARY KEY,   -- the user id (identity owns users; no FK: identity may move out)
    display_name               varchar(80),
    city                       varchar(80),
    state                      varchar(2),
    languages                  text[]       NOT NULL DEFAULT '{}',
    categories                 text[]       NOT NULL DEFAULT '{}',
    formats                    text[]       NOT NULL DEFAULT '{}',
    rate_per_reel_rupees       integer      CHECK (rate_per_reel_rupees BETWEEN 100 AND 500000),
    partnership_ads            boolean,
    turnaround_days            smallint     CHECK (turnaround_days BETWEEN 1 AND 30),
    bio                        varchar(300),
    sample_links               text[]       NOT NULL DEFAULT '{}',
    -- Typed by the creator (manual path) or confirmed by Backend A (Instagram path). Not unique: a self-reported
    -- handle proves nothing, and a unique index would let anyone block a real creator by typing her handle first.
    instagram_handle           varchar(30),
    instagram_status           varchar(16)  NOT NULL DEFAULT 'NOT_CONNECTED'
        CHECK (instagram_status IN ('NOT_CONNECTED', 'SYNCING', 'ACTIVE', 'ERROR', 'EXPIRED', 'REVOKED')),
    instagram_insights_allowed boolean,
    instagram_checked_at       timestamptz,
    level                      varchar(8)   NOT NULL DEFAULT 'NEW' CHECK (level IN ('NEW', 'VERIFIED')),
    level_reason               varchar(300),
    marketplace_status         varchar(8)   NOT NULL DEFAULT 'LISTED'
        CHECK (marketplace_status IN ('LISTED', 'HIDDEN', 'BLOCKED')),
    marketplace_reason         varchar(300),
    completed_at               timestamptz,
    created_at                 timestamptz  NOT NULL,
    updated_at                 timestamptz  NOT NULL,
    version                    bigint       NOT NULL DEFAULT 0,
    CHECK (marketplace_status <> 'BLOCKED' OR marketplace_reason IS NOT NULL),
    CHECK (level <> 'VERIFIED' OR level_reason IS NOT NULL)
);
-- F3 filters (category, city, language, rate, format, level) read bookable creators.
CREATE INDEX creator_profiles_bookable_idx ON creator_profiles (level, rate_per_reel_rupees)
    WHERE completed_at IS NOT NULL AND marketplace_status = 'LISTED';

-- One current value per metric and source, so brands see where each number came from and when.
CREATE TABLE creator_metrics (
    creator_id   uuid         NOT NULL REFERENCES creator_profiles (user_id),
    metric       varchar(12)  NOT NULL CHECK (metric IN ('FOLLOWERS', 'AVG_VIEWS_10')),
    source       varchar(14)  NOT NULL CHECK (source IN ('SELF_REPORTED', 'OPS_CHECKED')),
    value        bigint       NOT NULL CHECK (value BETWEEN 0 AND 1000000000),
    captured_on  date         NOT NULL,
    set_by       uuid         NOT NULL,
    updated_at   timestamptz  NOT NULL,
    PRIMARY KEY (creator_id, metric, source)
);

-- Every storage key we hand out, and who may use it. A profile or job accepts only a key issued here to that person.
CREATE TABLE uploads (
    key                 varchar(200) PRIMARY KEY,
    owner_id            uuid         NOT NULL,
    purpose             varchar(20)  NOT NULL CHECK (purpose IN ('CREATOR_EVIDENCE')),
    content_type        varchar(40)  NOT NULL,
    max_bytes           integer      NOT NULL,
    issued_at           timestamptz  NOT NULL,
    expires_at          timestamptz  NOT NULL,
    used_at             timestamptz,
    size_bytes          bigint,
    delete_requested_at timestamptz,
    deleted_at          timestamptz
);
CREATE INDEX uploads_owner_idx ON uploads (owner_id);
-- Issued but never used: removed from storage by the cleanup (a stale ticket is harmless, the object is not).
CREATE INDEX uploads_unused_idx ON uploads (expires_at) WHERE used_at IS NULL AND deleted_at IS NULL;

CREATE TABLE creator_evidence (
    id           uuid         PRIMARY KEY,
    creator_id   uuid         NOT NULL REFERENCES creator_profiles (user_id),
    upload_key   varchar(200) NOT NULL UNIQUE REFERENCES uploads (key),
    kind         varchar(12)  NOT NULL CHECK (kind IN ('INSIGHTS', 'COLLAB_PROOF')),
    status       varchar(8)   NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'REMOVED')),
    review_note  varchar(300),
    reviewed_by  uuid,
    reviewed_at  timestamptz,
    created_at   timestamptz  NOT NULL,
    CHECK (status <> 'REJECTED' OR review_note IS NOT NULL)
);
CREATE INDEX creator_evidence_creator_idx ON creator_evidence (creator_id);
-- The ops verification queue: oldest pending first.
CREATE INDEX creator_evidence_pending_idx ON creator_evidence (created_at) WHERE status = 'PENDING';
