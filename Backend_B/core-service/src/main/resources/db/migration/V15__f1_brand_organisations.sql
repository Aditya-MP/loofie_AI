-- F1 round B: brand organisations (docs/features/f1-identity-round-b.md section 8).

-- A brand's company. GSTIN is optional until the first brief (E3); one GSTIN per company unless ops allowed a share.
CREATE TABLE organizations (
    id                  uuid         PRIMARY KEY,
    legal_name          varchar(200) NOT NULL,
    gstin               varchar(15),
    gstin_shared_reason varchar(500),                 -- set only when ops allowed a second company on this GSTIN
    gstin_set_by        uuid,
    gstin_set_at        timestamptz,
    billing_line1       varchar(200) NOT NULL,
    billing_line2       varchar(200),
    billing_city        varchar(100) NOT NULL,
    billing_state       varchar(2)   NOT NULL,
    billing_pincode     varchar(6)   NOT NULL,
    pickup_state        varchar(2)   NOT NULL,         -- where parcels leave from: decides state holidays
    created_by          uuid         NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CHECK (gstin_shared_reason IS NULL OR gstin IS NOT NULL)
);
CREATE UNIQUE INDEX organizations_gstin_uq ON organizations (gstin)
    WHERE gstin IS NOT NULL AND gstin_shared_reason IS NULL;
CREATE INDEX organizations_gstin_idx ON organizations (gstin) WHERE gstin IS NOT NULL;

CREATE TABLE org_members (
    org_id   uuid        NOT NULL REFERENCES organizations (id),
    user_id  uuid        NOT NULL REFERENCES users (id),
    role     varchar(12) NOT NULL CHECK (role IN ('BRAND_ADMIN', 'BRAND_MEMBER')),
    added_by uuid,
    added_at timestamptz NOT NULL,
    PRIMARY KEY (org_id, user_id)
);
-- One company per person in v1 (Q6). Agencies later: drop this index, the table already allows many.
CREATE UNIQUE INDEX org_members_one_org_uq ON org_members (user_id);

-- One invites table for staff and brand teammates (E1). Round A rows stay as they are (org_id null = staff).
ALTER TABLE staff_invites RENAME TO invites;
ALTER TABLE invites RENAME COLUMN staff_role TO role;
ALTER TABLE invites DROP CONSTRAINT staff_invites_staff_role_check;
ALTER TABLE invites ALTER COLUMN role TYPE varchar(12);
ALTER TABLE invites ADD COLUMN org_id uuid REFERENCES organizations (id);
ALTER TABLE invites ADD COLUMN revoked_at timestamptz;
ALTER TABLE invites ADD CONSTRAINT invites_role_check CHECK (
    (org_id IS NULL AND role IN ('OPS', 'OPS_LEAD', 'FOUNDER'))
    OR (org_id IS NOT NULL AND role IN ('BRAND_ADMIN', 'BRAND_MEMBER')));
CREATE INDEX invites_org_open_idx ON invites (org_id) WHERE accepted_at IS NULL AND revoked_at IS NULL;

-- The brand's pickup state when the job was created (E2): brand-side working-day clocks skip that state's holidays.
ALTER TABLE jobs ADD COLUMN brand_pickup_state varchar(2);

ALTER TABLE audit_log ADD COLUMN target_org_id uuid;
CREATE INDEX audit_log_target_org_idx ON audit_log (target_org_id, at DESC) WHERE target_org_id IS NOT NULL;
