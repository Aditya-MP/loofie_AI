-- F5 round 2 (docs/features/f5-job-engine.md "Round 2"): price snapshot, refund approvals, notification inbox,
-- senior ops roles. Expand only.

-- R7: senior ops roles appear as actors in the audit log.
ALTER TABLE job_events DROP CONSTRAINT job_events_actor_type_check;
ALTER TABLE job_events ADD CONSTRAINT job_events_actor_type_check
    CHECK (actor_type IN ('BRAND_ADMIN', 'BRAND_MEMBER', 'CREATOR', 'OPS', 'OPS_LEAD', 'FOUNDER', 'SYSTEM'));

-- R1: what the brand was charged per slot, fixed when it paid (basis points: 1500 = 15%).
-- Existing orders get today's terms (DECISIONS.md section 4 example: 15% fee, 18% GST).
ALTER TABLE orders
    ADD COLUMN loofie_fee_bp int NOT NULL DEFAULT 1500 CHECK (loofie_fee_bp BETWEEN 0 AND 10000),
    ADD COLUMN gst_bp        int NOT NULL DEFAULT 1800 CHECK (gst_bp BETWEEN 0 AND 10000);

-- R2: an open refund request holds the job: no other action runs until it is approved, rejected or withdrawn.
ALTER TABLE jobs ADD COLUMN refund_hold boolean NOT NULL DEFAULT false;

CREATE TABLE refund_requests (
    id                    uuid         PRIMARY KEY,
    job_id                uuid         NOT NULL REFERENCES jobs (id),
    brand_org_id          uuid         NOT NULL,
    action                varchar(32)  NOT NULL CHECK (action IN ('CANCEL', 'RESOLVE_REFUND', 'REPLACEMENT_EXPIRED')),
    refund_case           varchar(16)  NOT NULL CHECK (refund_case IN ('FULL', 'MINUS_FEE', 'LATE_SHIP', 'KILL_FEE')),
    reason_code           varchar(32)  NOT NULL CHECK (reason_code IN ('BRAND_CANCELLED', 'LOOFIE_NO_CREATOR',
                                                                       'DISPUTE_UPHELD', 'BRAND_LATE_SHIP', 'OTHER')),
    note                  varchar(1000),                -- ops free text: stays in core, never sent out
    brand_cash_paise      bigint       NOT NULL CHECK (brand_cash_paise >= 0),
    brand_credit_paise    bigint       NOT NULL CHECK (brand_credit_paise >= 0),
    creator_kill_fee_paise bigint      NOT NULL CHECK (creator_kill_fee_paise >= 0),
    creator_accepted      boolean      NOT NULL,        -- had any creator accepted this job (four-eyes rule)
    lead_may_self_approve boolean      NOT NULL,
    founder_required      boolean      NOT NULL,
    requested_by          uuid,                         -- null = the timer (SYSTEM)
    requested_by_role     varchar(16)  NOT NULL,
    requested_at          timestamptz  NOT NULL,
    status                varchar(16)  NOT NULL CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    lead_approved_by      uuid,
    lead_approved_at      timestamptz,
    founder_approved_by   uuid,
    founder_approved_at   timestamptz,
    decided_by            uuid,
    decided_at            timestamptz,
    decision_note         varchar(1000),
    version               bigint       NOT NULL,
    CHECK (founder_approved_by IS NULL OR founder_approved_by IS DISTINCT FROM lead_approved_by)
);
-- One open request per job.
CREATE UNIQUE INDEX refund_requests_one_open_idx ON refund_requests (job_id) WHERE status = 'REQUESTED';
CREATE INDEX refund_requests_brand_idx ON refund_requests (brand_org_id, decided_at) WHERE status = 'APPROVED';
CREATE INDEX refund_requests_open_idx ON refund_requests (requested_at) WHERE status = 'REQUESTED';
CREATE INDEX refund_requests_decided_idx ON refund_requests (decided_at) WHERE decided_at IS NOT NULL;

-- A decided request can never change, and no request is ever deleted ("an approved request can't be edited").
CREATE FUNCTION refund_requests_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status <> 'REQUESTED' THEN
        RAISE EXCEPTION 'refund request % is % and cannot be changed', OLD.id, OLD.status
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER refund_requests_immutable BEFORE UPDATE OR DELETE ON refund_requests
    FOR EACH ROW EXECUTE FUNCTION refund_requests_guard();

-- R5: what each person sees in the app. Ids and codes only (no personal data), kept 90 days.
CREATE TABLE notifications (
    id             uuid        PRIMARY KEY,
    recipient_type varchar(16) NOT NULL CHECK (recipient_type IN ('BRAND_ORG', 'CREATOR', 'OPS', 'FOUNDER')),
    recipient_id   uuid,                                 -- brand org or creator; null for the shared ops/founder inbox
    kind           varchar(48) NOT NULL,
    job_id         uuid,
    payload        jsonb       NOT NULL,
    created_at     timestamptz NOT NULL,
    read_at        timestamptz,
    CHECK ((recipient_type IN ('BRAND_ORG', 'CREATOR')) = (recipient_id IS NOT NULL))
);
CREATE INDEX notifications_inbox_idx ON notifications (recipient_type, recipient_id, created_at DESC);
CREATE INDEX notifications_created_idx ON notifications (created_at);
