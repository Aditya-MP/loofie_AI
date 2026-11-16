-- F6 round 2 (docs/features/f6-product-delivery.md "Round 2"): couriers table, duplicate tracking numbers,
-- late damage reports, DEFECTIVE_PRODUCT. Expand only: the previous app version still works against this schema
-- (it writes courier + tracking_number as before; the new columns are nullable).

-- A: new reason code (BRAND fault). Same list as ReasonCode.java.
ALTER TABLE job_events DROP CONSTRAINT job_events_reason_code_check;
ALTER TABLE job_events ADD CONSTRAINT job_events_reason_code_check CHECK (reason_code IN (
    'FALSE_CLAIM', 'CREATOR_UNRESPONSIVE', 'CREATOR_WITHDREW', 'BRAND_LATE_SHIP', 'WRONG_ITEM_SENT',
    'DEFECTIVE_PRODUCT', 'COURIER_LOST', 'DAMAGED_IN_TRANSIT', 'OTHER'));

-- D: couriers ops can edit. kind: TRACKED needs a tracking number, LOCAL a handover photo, OTHER a name + link.
-- tracking_url_pattern: https URL with {tracking} where the number goes; ops fills it in (not seeded from memory).
CREATE TABLE couriers (
    code                 varchar(32)  PRIMARY KEY CHECK (code ~ '^[A-Z0-9_]{2,32}$'),
    name                 varchar(64)  NOT NULL CHECK (length(trim(name)) > 0),
    kind                 varchar(8)   NOT NULL CHECK (kind IN ('TRACKED', 'LOCAL', 'OTHER')),
    tracking_url_pattern varchar(300) CHECK (tracking_url_pattern IS NULL
                                             OR (tracking_url_pattern LIKE 'https://%' AND tracking_url_pattern LIKE '%{tracking}%')),
    active               boolean      NOT NULL DEFAULT true,
    sort_order           int          NOT NULL DEFAULT 100,
    updated_by           uuid,
    updated_at           timestamptz  NOT NULL DEFAULT now()
);
INSERT INTO couriers (code, name, kind, sort_order) VALUES
    ('DELHIVERY', 'Delhivery', 'TRACKED', 10),
    ('BLUE_DART', 'Blue Dart', 'TRACKED', 20),
    ('DTDC', 'DTDC', 'TRACKED', 30),
    ('ECOM_EXPRESS', 'Ecom Express', 'TRACKED', 40),
    ('XPRESSBEES', 'Xpressbees', 'TRACKED', 50),
    ('SHADOWFAX', 'Shadowfax', 'TRACKED', 60),
    ('INDIA_POST', 'India Post', 'TRACKED', 70),
    ('EKART', 'Ekart', 'TRACKED', 80),
    ('SHIPROCKET', 'Shiprocket', 'TRACKED', 90),
    ('LOCAL', 'Local delivery (Porter, Rapido, own staff)', 'LOCAL', 900),
    ('OTHER', 'Other', 'OTHER', 999);

-- D + B: what the brand chose, and what we need to check the number later.
ALTER TABLE shipments
    ADD COLUMN courier_code       varchar(32) REFERENCES couriers (code),
    ADD COLUMN tracking_url       varchar(300) CHECK (tracking_url IS NULL OR tracking_url LIKE 'https://%'),
    ADD COLUMN handover_photo_ref varchar(512) CHECK (handover_photo_ref IS NULL OR handover_photo_ref ~
        '^handovers/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|jpeg|png|heic|webp)$'),
    ADD COLUMN tracking_norm      varchar(64),
    ADD COLUMN tracking_reuse     varchar(32) CHECK (tracking_reuse IS NULL OR tracking_reuse = 'SAME_BRAND_OTHER_CREATOR'),
    -- E: a creator's damage report after receipt, with its evidence
    ADD COLUMN damage_reported_at  timestamptz,
    ADD COLUMN damage_evidence_ref varchar(512) CHECK (damage_evidence_ref IS NULL OR damage_evidence_ref ~
        '^damage/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|jpeg|png|heic|webp|mp4|mov)$');

-- Local delivery has no tracking number: allowed only with a handover photo.
ALTER TABLE shipments ALTER COLUMN tracking_number DROP NOT NULL;
ALTER TABLE shipments DROP CONSTRAINT shipments_tracking_number_check;
ALTER TABLE shipments ADD CONSTRAINT shipments_tracking_number_check
    CHECK (tracking_number IS NULL OR length(trim(tracking_number)) > 0);
ALTER TABLE shipments ADD CONSTRAINT shipments_number_or_handover
    CHECK (tracking_number IS NOT NULL OR handover_photo_ref IS NOT NULL);

UPDATE shipments SET tracking_norm = upper(regexp_replace(tracking_number, '[\s-]', '', 'g'))
WHERE tracking_number IS NOT NULL;
CREATE INDEX shipments_tracking_norm_idx ON shipments (tracking_norm) WHERE tracking_norm IS NOT NULL;

-- E: the clock stops on a late damage report; the time left is kept for "the product is usable after all".
ALTER TABLE jobs
    ADD COLUMN production_paused_seconds bigint CHECK (production_paused_seconds IS NULL OR production_paused_seconds >= 0),
    ADD COLUMN late_damage_reported      boolean NOT NULL DEFAULT false;
