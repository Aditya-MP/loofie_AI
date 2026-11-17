-- F1 round A: identity core (docs/features/f1-identity-round-a.md section 8).
-- Owned by the identity package only; other packages see a user through ActorResolver, never these tables.

-- One row per person. users.id is the id used everywhere else (jobs.creator_id, Actor.userId, Backend A account_ref).
CREATE TABLE users (
    id                   uuid         PRIMARY KEY,
    account_type         varchar(8)   NOT NULL CHECK (account_type IN ('BRAND', 'CREATOR', 'ADMIN')),
    status               varchar(10)  NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    staff_role           varchar(10)  CHECK (staff_role IN ('OPS', 'OPS_LEAD', 'FOUNDER')),
    phone                varchar(16),                 -- E.164, +91 only in v1
    email                varchar(254),                -- lower-case
    phone_verified_at    timestamptz,
    email_verified_at    timestamptz,
    adult_confirmed_at   timestamptz,
    onboarding_completed boolean      NOT NULL DEFAULT false,
    totp_secret_enc      bytea,                       -- AES-GCM, key LOOFIE_TOTP_KEY
    totp_confirmed_at    timestamptz,
    totp_last_step       bigint,                      -- last accepted 30 s step: a code works once
    status_reason        varchar(500),
    status_changed_at    timestamptz,
    status_changed_by    uuid,
    created_at           timestamptz  NOT NULL,
    updated_at           timestamptz  NOT NULL,
    CHECK ((account_type = 'ADMIN') = (staff_role IS NOT NULL)),
    CHECK (status = 'DELETED' OR phone IS NOT NULL OR email IS NOT NULL)
);
-- One person per phone and per email. Deleted accounts clear both, so the number can sign up again.
CREATE UNIQUE INDEX users_phone_uq ON users (phone) WHERE phone IS NOT NULL;
CREATE UNIQUE INDEX users_email_uq ON users (email) WHERE email IS NOT NULL;
CREATE INDEX users_founders_idx ON users (staff_role) WHERE staff_role IS NOT NULL;

-- Consent history (DPDP Act): a row per grant; withdrawal sets withdrawn_at, never deletes.
CREATE TABLE consents (
    id             uuid         PRIMARY KEY,
    user_id        uuid         NOT NULL REFERENCES users (id),
    purpose        varchar(12)  NOT NULL CHECK (purpose IN ('SERVICE', 'MARKETING')),
    notice_version varchar(40)  NOT NULL,
    granted_at     timestamptz  NOT NULL,
    withdrawn_at   timestamptz,
    ip             varchar(45)
);
CREATE INDEX consents_user_idx ON consents (user_id, purpose, granted_at DESC);

-- One OTP sent. Only an HMAC of the code is stored.
CREATE TABLE otp_challenges (
    id           uuid         PRIMARY KEY,
    purpose      varchar(12)  NOT NULL CHECK (purpose IN ('LOGIN', 'CHANGE_OLD', 'CHANGE_NEW')),
    channel      varchar(5)   NOT NULL CHECK (channel IN ('PHONE', 'EMAIL')),
    destination  varchar(254) NOT NULL,
    user_id      uuid,                                -- set for contact changes
    code_hmac    bytea        NOT NULL,
    attempts     smallint     NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL,
    expires_at   timestamptz  NOT NULL,
    verified_at  timestamptz,
    consumed_at  timestamptz,
    failed_at    timestamptz,                         -- the SMS / email could not be sent
    ip           varchar(45)
);
CREATE INDEX otp_challenges_created_idx ON otp_challenges (created_at);

-- Logged-in devices. Only a SHA-256 of the cookie value is stored.
CREATE TABLE sessions (
    id                  uuid         PRIMARY KEY,
    token_hash          bytea        NOT NULL UNIQUE,
    user_id             uuid         NOT NULL REFERENCES users (id),
    kind                varchar(12)  NOT NULL CHECK (kind IN ('FULL', 'MFA_PENDING', 'MFA_ENROL')),
    created_at          timestamptz  NOT NULL,
    expires_at          timestamptz  NOT NULL,
    absolute_expires_at timestamptz  NOT NULL,
    last_seen_at        timestamptz  NOT NULL,
    revoked_at          timestamptz,
    revoke_reason       varchar(24),
    ip                  varchar(45),
    user_agent          varchar(200)
);
CREATE INDEX sessions_user_live_idx ON sessions (user_id) WHERE revoked_at IS NULL;
CREATE INDEX sessions_expires_idx ON sessions (expires_at);

-- Fixed-window counters for rate limits and the SMS spend cap; shared by every instance.
CREATE TABLE rate_counters (
    key          varchar(300) NOT NULL,
    window_start timestamptz  NOT NULL,
    count        integer      NOT NULL,
    PRIMARY KEY (key, window_start)
);
CREATE INDEX rate_counters_window_idx ON rate_counters (window_start);

CREATE TABLE staff_invites (
    id               uuid         PRIMARY KEY,
    email            varchar(254) NOT NULL,
    staff_role       varchar(10)  NOT NULL CHECK (staff_role IN ('OPS', 'OPS_LEAD', 'FOUNDER')),
    token_hash       bytea        NOT NULL UNIQUE,
    invited_by       uuid         NOT NULL,
    created_at       timestamptz  NOT NULL,
    expires_at       timestamptz  NOT NULL,
    accepted_at      timestamptz,
    accepted_user_id uuid
);

CREATE TABLE recovery_codes (
    id         uuid         PRIMARY KEY,
    user_id    uuid         NOT NULL REFERENCES users (id),
    code_hmac  bytea        NOT NULL,
    created_at timestamptz  NOT NULL,
    used_at    timestamptz
);
CREATE INDEX recovery_codes_user_idx ON recovery_codes (user_id) WHERE used_at IS NULL;

-- Every staff action on a person (and contact changes). Append-only; kept 1 year (loofie.retention.audit-log).
CREATE TABLE audit_log (
    id             uuid         PRIMARY KEY,
    at             timestamptz  NOT NULL,
    actor_id       uuid,
    actor_role     varchar(16)  NOT NULL,
    action         varchar(40)  NOT NULL,
    target_user_id uuid,
    reason         varchar(500),
    details        jsonb,
    request_id     uuid
);
CREATE INDEX audit_log_target_idx ON audit_log (target_user_id, at DESC);
CREATE INDEX audit_log_at_idx ON audit_log (at DESC);

-- A suspended creator's open jobs are listed for ops (round A, P6).
CREATE INDEX assignments_active_creator_idx ON assignments (creator_id) WHERE status = 'ACTIVE';
