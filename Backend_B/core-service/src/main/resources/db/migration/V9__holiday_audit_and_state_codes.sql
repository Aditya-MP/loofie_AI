-- Working-day calendar hardening (docs/features/working-day-calendar.md). Expand only; the tables are tiny.

-- G2: every holiday change, so "was this a holiday when that clock started?" can be answered. Append-only.
CREATE TABLE holiday_changes (
    id           bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    changed_at   timestamptz  NOT NULL,
    changed_by   uuid,                                -- null = migration seed
    action       varchar(8)   NOT NULL CHECK (action IN ('INSERT', 'RENAME', 'DELETE')),
    holiday_date date         NOT NULL,
    state_code   varchar(8)   NOT NULL,
    old_name     varchar(100),
    new_name     varchar(100)
);
CREATE INDEX holiday_changes_date_idx ON holiday_changes (holiday_date);

CREATE FUNCTION holiday_changes_reject_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'holiday_changes is append-only: % rejected', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;
CREATE TRIGGER holiday_changes_no_update_delete
    BEFORE UPDATE OR DELETE ON holiday_changes
    FOR EACH ROW EXECUTE FUNCTION holiday_changes_reject_change();
CREATE TRIGGER holiday_changes_no_truncate
    BEFORE TRUNCATE ON holiday_changes
    FOR EACH STATEMENT EXECUTE FUNCTION holiday_changes_reject_change();

-- The history starts complete: today's rows as their original inserts.
INSERT INTO holiday_changes (changed_at, changed_by, action, holiday_date, state_code, new_name)
SELECT created_at, created_by, 'INSERT', holiday_date, state_code, name FROM holidays ORDER BY created_at;

-- G3: only real state / union territory codes (ISO 3166-2:IN, 2023), '' = national. Same list as StateCodes.java.
ALTER TABLE holidays ADD CONSTRAINT holidays_state_code_check CHECK (state_code IN ('',
    'AP', 'AR', 'AS', 'BR', 'CG', 'GA', 'GJ', 'HR', 'HP', 'JH', 'KA', 'KL', 'MP', 'MH', 'MN', 'ML', 'MZ', 'NL',
    'OD', 'PB', 'RJ', 'SK', 'TN', 'TS', 'TR', 'UP', 'UK', 'WB',
    'AN', 'CH', 'DH', 'DL', 'JK', 'LA', 'LD', 'PY'));
