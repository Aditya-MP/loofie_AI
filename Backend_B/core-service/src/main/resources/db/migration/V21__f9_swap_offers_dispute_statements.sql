-- F9 disputes and replacement.

-- Ops offers the next creator; the brand approves or declines (Foundation v2 F9: "ops offers the next creator from the
-- shortlist at the same or a lower rate. The brand approves the swap."). At most one open offer per job.
CREATE TABLE job_swap_offers (
    id                   uuid         PRIMARY KEY,
    job_id               uuid         NOT NULL REFERENCES jobs (id),
    creator_id           uuid         NOT NULL,
    rate_per_reel_rupees integer      NOT NULL CHECK (rate_per_reel_rupees > 0), -- her listed rate when offered
    status               varchar(12)  NOT NULL CHECK (status IN ('OFFERED', 'APPROVED', 'DECLINED', 'WITHDRAWN')),
    note                 varchar(500),            -- ops' note, inside Loofie only
    suggestion_id        varchar(100),            -- Backend A's shortlist suggestion, when ops took it from there
    offered_by           uuid         NOT NULL,
    offered_at           timestamptz  NOT NULL,
    decided_by           uuid,
    decided_at           timestamptz,
    decline_reason       varchar(500),            -- the brand's reason, shown to ops
    closed_reason        varchar(40)              -- WITHDRAWN: OPS, or the job ended (REFUNDED, CANCELLED)
);
CREATE UNIQUE INDEX job_swap_offers_one_open ON job_swap_offers (job_id) WHERE status = 'OFFERED';
CREATE INDEX job_swap_offers_job_idx ON job_swap_offers (job_id, offered_at);

-- The creator's side of a dispute: one statement per assignment, while the job is DISPUTED. Ops reads it with the
-- brand's note and checklist before deciding.
CREATE TABLE job_dispute_statements (
    assignment_id uuid          PRIMARY KEY REFERENCES assignments (id),
    job_id        uuid          NOT NULL REFERENCES jobs (id),
    creator_id    uuid          NOT NULL,
    statement     varchar(2000) NOT NULL CHECK (length(statement) >= 20),
    created_at    timestamptz   NOT NULL
);
CREATE INDEX job_dispute_statements_job_idx ON job_dispute_statements (job_id);
