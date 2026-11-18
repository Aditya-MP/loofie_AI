-- F7 draft upload and review (docs/features/f7-draft-review.md).

-- Upload register purposes: F7 videos, and the F6 photos moved onto the register (D8).
ALTER TABLE uploads DROP CONSTRAINT uploads_purpose_check;
ALTER TABLE uploads ADD CONSTRAINT uploads_purpose_check CHECK (purpose IN (
    'CREATOR_EVIDENCE', 'DRAFT_VIDEO', 'DRAFT_PREVIEW', 'RECEIPT_PHOTO', 'HANDOVER_PHOTO', 'DAMAGE_EVIDENCE'));

-- What the brand ordered, as far as the video checks need it (D1): a copy taken when the job is set up, so a later
-- edit of the brief in Backend A never changes the contract. Ops enters it until Backend A sends it.
CREATE TABLE job_briefs (
    job_id         uuid        PRIMARY KEY REFERENCES jobs (id),
    checklist      jsonb       NOT NULL,           -- [{"key": "hook", "label": "..."}], 1-12 items
    min_seconds    integer     NOT NULL,
    max_seconds    integer     NOT NULL,
    ad_rights_days integer,                        -- how long the brand may run the video (D9 retention)
    source         varchar(10) NOT NULL CHECK (source IN ('BACKEND_A', 'OPS')),
    set_by         uuid,
    set_at         timestamptz NOT NULL,
    CHECK (min_seconds >= 1 AND max_seconds <= 600 AND min_seconds <= max_seconds),
    CHECK (ad_rights_days IS NULL OR ad_rights_days BETWEEN 1 AND 3650),
    CHECK (jsonb_typeof(checklist) = 'array' AND jsonb_array_length(checklist) BETWEEN 1 AND 12)
);

-- One row per uploaded video. Also the processing queue: CHECKING / PREVIEWING rows are worked by the media worker
-- (lease, retry with back-off). Only READY drafts are ever shown to the brand.
CREATE TABLE job_drafts (
    id            uuid         PRIMARY KEY,
    job_id        uuid         NOT NULL REFERENCES jobs (id),
    creator_id    uuid         NOT NULL,
    attempt       integer      NOT NULL CHECK (attempt BETWEEN 1 AND 20),
    kind          varchar(8)   NOT NULL CHECK (kind IN ('FIRST', 'REVISION')),
    master_key    varchar(200) NOT NULL UNIQUE,
    master_bytes  bigint       NOT NULL,
    preview_key   varchar(200) UNIQUE,
    status        varchar(12)  NOT NULL CHECK (status IN ('CHECKING', 'PREVIEWING', 'READY', 'FAILED',
                                                          'SUPERSEDED', 'PURGED')),
    probe         jsonb,                           -- what ffprobe found: width, height, seconds, audio, codec
    failures      jsonb,                           -- [{"code": "NOT_PORTRAIT", "message": "..."}]
    submitted_at  timestamptz  NOT NULL,           -- counts for "on time", not when processing finished
    ready_at      timestamptz,
    failed_at     timestamptz,
    approved_at   timestamptz,
    delete_after  timestamptz,                     -- D9: files removed by the retention sweep after this
    tries         integer      NOT NULL DEFAULT 0,
    next_try_at   timestamptz  NOT NULL,
    lease_until   timestamptz,
    last_error    varchar(500),
    ops_alerted   boolean      NOT NULL DEFAULT false,
    updated_at    timestamptz  NOT NULL,
    UNIQUE (job_id, attempt),
    CHECK (status <> 'READY' OR preview_key IS NOT NULL)
);
-- At most one video in processing per job: a double tap or two devices cannot queue two.
CREATE UNIQUE INDEX job_drafts_one_in_flight_uq ON job_drafts (job_id) WHERE status IN ('CHECKING', 'PREVIEWING');
CREATE INDEX job_drafts_queue_idx ON job_drafts (next_try_at) WHERE status IN ('CHECKING', 'PREVIEWING');
CREATE INDEX job_drafts_job_idx ON job_drafts (job_id, attempt DESC);
CREATE INDEX job_drafts_retention_idx ON job_drafts (delete_after) WHERE delete_after IS NOT NULL AND status <> 'PURGED';

-- The brand's answers to the checklist, one row per decision.
CREATE TABLE draft_reviews (
    id            uuid          PRIMARY KEY,
    job_id        uuid          NOT NULL REFERENCES jobs (id),
    draft_id      uuid          NOT NULL REFERENCES job_drafts (id),
    decision      varchar(8)    NOT NULL CHECK (decision IN ('APPROVE', 'REVISION', 'REJECT')),
    answers       jsonb         NOT NULL,          -- [{"key": "hook", "ok": false, "note": "..."}]
    note          varchar(1000),
    reviewer_id   uuid          NOT NULL,
    reviewer_role varchar(16)   NOT NULL,
    reviewed_at   timestamptz   NOT NULL
);
CREATE INDEX draft_reviews_job_idx ON draft_reviews (job_id, reviewed_at DESC);
CREATE UNIQUE INDEX draft_reviews_one_per_draft_uq ON draft_reviews (draft_id);

-- The brand's rating of the creator (D7): once per job, within 7 days of approval. Stars go to Backend A; the
-- comment stays here (free text never leaves core).
CREATE TABLE job_ratings (
    job_id     uuid          PRIMARY KEY REFERENCES jobs (id),
    creator_id uuid          NOT NULL,
    stars      smallint      NOT NULL CHECK (stars BETWEEN 1 AND 5),
    comment    varchar(1000),
    rated_by   uuid          NOT NULL,
    rated_at   timestamptz   NOT NULL
);
