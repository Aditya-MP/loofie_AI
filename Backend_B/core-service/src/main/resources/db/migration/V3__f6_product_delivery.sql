-- F6 product delivery (rules: Backend_B/docs/product-decisions.md, loofie-ai/docs/DECISIONS.md section 5).
--  * The brand ships within 3 working days of acceptance, with courier name + tracking number.
--  * The creator taps Received (photo) / Not received / Damaged-wrong item.
--  * The production clock auto-starts only 24h after the courier shows "Delivered" with no problem reported.
--    Without courier proof it never auto-starts: the creator is reminded, then ops checks.

-- New state: DELIVERY_ISSUE (production clock stopped while in it).
ALTER TABLE jobs DROP CONSTRAINT jobs_status_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_status_check CHECK (status IN (
    'AWAITING_PAYMENT', 'PAID', 'CREATOR_INVITED', 'CREATOR_ACCEPTED', 'DECLINED',
    'PRODUCT_SHIPPED', 'DELIVERY_ISSUE', 'PRODUCT_RECEIVED', 'IN_PRODUCTION', 'DRAFT_SUBMITTED',
    'IN_REVIEW', 'REVISION_REQUESTED', 'APPROVED', 'DISPUTED', 'REPLACEMENT_NEEDED',
    'REFUNDED', 'PAYOUT_PENDING', 'PAID_OUT', 'CLOSED', 'CANCELLED'));

-- The old 96h fallback auto-started production. Now it only tells ops to check.
ALTER TABLE jobs RENAME COLUMN receipt_fallback_at TO receipt_ops_check_at;
ALTER INDEX jobs_receipt_fallback_idx RENAME TO jobs_receipt_ops_check_idx;

ALTER TABLE jobs
    ADD COLUMN delivery_issue_type   varchar(16) CHECK (delivery_issue_type IN ('NOT_RECEIVED', 'DAMAGED', 'WRONG_ITEM')),
    ADD COLUMN delivery_issue_at     timestamptz,
    ADD COLUMN first_shipped_at      timestamptz,   -- the 7-day "still missing" rule counts from the FIRST shipment
    ADD COLUMN last_shipped_at       timestamptz,
    ADD COLUMN courier_delivered_at  timestamptz,   -- courier shows "Delivered" (ops by hand in the pilot)
    -- New deadlines
    ADD COLUMN ship_reminder_at      timestamptz,   -- CREATOR_ACCEPTED + 2 working days (remind brand)
    ADD COLUMN ship_due_at           timestamptz,   -- CREATOR_ACCEPTED + 3 working days (late: creator may withdraw)
    ADD COLUMN auto_start_at         timestamptz,   -- courier "Delivered" + 24h (production auto-starts)
    ADD COLUMN issue_response_due_at timestamptz,   -- DELIVERY_ISSUE + 48h (brand shows tracking or reships)
    ADD COLUMN missing_escalate_at   timestamptz;   -- first "Shipped" + 7 days while in DELIVERY_ISSUE (ops steps in)

CREATE INDEX jobs_ship_reminder_idx      ON jobs (ship_reminder_at)      WHERE ship_reminder_at IS NOT NULL;
CREATE INDEX jobs_ship_due_idx           ON jobs (ship_due_at)           WHERE ship_due_at IS NOT NULL;
CREATE INDEX jobs_auto_start_idx         ON jobs (auto_start_at)         WHERE auto_start_at IS NOT NULL;
CREATE INDEX jobs_issue_response_due_idx ON jobs (issue_response_due_at) WHERE issue_response_due_at IS NOT NULL;
CREATE INDEX jobs_missing_escalate_idx   ON jobs (missing_escalate_at)   WHERE missing_escalate_at IS NOT NULL;

-- One row per shipment attempt (a reship adds a row), so the courier evidence is never overwritten.
CREATE TABLE shipments (
    id                    uuid        PRIMARY KEY,
    job_id                uuid        NOT NULL REFERENCES jobs (id),
    assignment_id         uuid        NOT NULL REFERENCES assignments (id),
    attempt               int         NOT NULL CHECK (attempt >= 1),
    courier               varchar(64) NOT NULL CHECK (length(trim(courier)) > 0),
    tracking_number       varchar(64) NOT NULL CHECK (length(trim(tracking_number)) > 0),
    shipped_at            timestamptz NOT NULL,
    shipped_by            uuid        NOT NULL,
    courier_delivered_at  timestamptz,
    courier_delivered_by  uuid,                    -- ops user in the pilot; null when a tracking service reports it
    received_at           timestamptz,
    receipt_photo_ref     varchar(512),            -- object-storage key once F7 upload exists
    version               bigint      NOT NULL,
    UNIQUE (job_id, attempt)
);

-- Working-day calendar: Monday to Saturday, minus these dates. state_code '' = national holiday,
-- otherwise the holiday applies only to brands picking up in that state (e.g. 'WB' for Durga Puja).
CREATE TABLE holidays (
    holiday_date date        NOT NULL,
    state_code   varchar(8)  NOT NULL DEFAULT '',
    name         varchar(100) NOT NULL,
    created_by   uuid,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (holiday_date, state_code)
);

-- Fixed-date national holidays only. Diwali, Holi and state holidays move every year: ops adds them.
INSERT INTO holidays (holiday_date, name) VALUES
    ('2026-01-26', 'Republic Day'),
    ('2026-08-15', 'Independence Day'),
    ('2026-10-02', 'Gandhi Jayanti'),
    ('2027-01-26', 'Republic Day'),
    ('2027-08-15', 'Independence Day'),
    ('2027-10-02', 'Gandhi Jayanti');

-- Reason codes: disputes and drop-outs carry a fixed code (never free text) for the AI feedback log.
ALTER TABLE job_events ADD COLUMN reason_code varchar(32) CHECK (reason_code IN (
    'FALSE_CLAIM', 'CREATOR_UNRESPONSIVE', 'CREATOR_WITHDREW', 'BRAND_LATE_SHIP', 'WRONG_ITEM_SENT',
    'COURIER_LOST', 'DAMAGED_IN_TRANSIT', 'OTHER'));
