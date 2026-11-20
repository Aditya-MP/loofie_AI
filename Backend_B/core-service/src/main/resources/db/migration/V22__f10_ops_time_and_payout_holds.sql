-- F10 ops console.

-- Ops minutes per job or per order (campaign): "staff time is the biggest cost ... log ops minutes per campaign in the
-- events log from campaign 1" (Foundation v2). Append-only like job_events: a mistake is fixed with a correction
-- entry (negative minutes, pointing at the entry it corrects), never by editing or deleting.
CREATE TABLE ops_time_entries (
    id          uuid         PRIMARY KEY,
    job_id      uuid         REFERENCES jobs (id),
    order_id    uuid         NOT NULL REFERENCES orders (id),   -- the job's order, or the order itself
    minutes     integer      NOT NULL CHECK (minutes BETWEEN -480 AND 480 AND minutes <> 0),
    activity    varchar(16)  NOT NULL CHECK (activity IN ('SHORTLIST', 'VERIFICATION', 'COORDINATION', 'DELIVERY',
                                                          'DISPUTE', 'REPLACEMENT', 'REFUND', 'OTHER')),
    note        varchar(200),
    corrects_id uuid         REFERENCES ops_time_entries (id),
    logged_by   uuid         NOT NULL,
    logged_role varchar(16)  NOT NULL,
    logged_at   timestamptz  NOT NULL,
    CHECK ((minutes < 0) = (corrects_id IS NOT NULL))       -- only a correction is negative, and it says what it corrects
);
CREATE INDEX ops_time_entries_job_idx ON ops_time_entries (job_id, logged_at) WHERE job_id IS NOT NULL;
CREATE INDEX ops_time_entries_order_idx ON ops_time_entries (order_id, logged_at);
CREATE INDEX ops_time_entries_logged_idx ON ops_time_entries (logged_at);
CREATE UNIQUE INDEX ops_time_entries_one_correction ON ops_time_entries (corrects_id) WHERE corrects_id IS NOT NULL;

CREATE FUNCTION ops_time_entries_reject_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ops_time_entries is append-only: % rejected', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;
CREATE TRIGGER ops_time_entries_no_update_delete BEFORE UPDATE OR DELETE ON ops_time_entries
    FOR EACH ROW EXECUTE FUNCTION ops_time_entries_reject_change();
CREATE TRIGGER ops_time_entries_no_truncate BEFORE TRUNCATE ON ops_time_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ops_time_entries_reject_change();

-- Payout holds: ops stops an APPROVED job's payout from being queued; an OPS_LEAD or FOUNDER other than the person who
-- placed it releases it. At most one open hold per job; the history stays.
CREATE TABLE payout_holds (
    id             uuid          PRIMARY KEY,
    job_id         uuid          NOT NULL REFERENCES jobs (id),
    placed_by      uuid          NOT NULL,
    placed_role    varchar(16)   NOT NULL,
    placed_at      timestamptz   NOT NULL,
    reason         varchar(500)  NOT NULL,
    released_by    uuid,
    released_role  varchar(16),
    released_at    timestamptz,
    release_reason varchar(500),
    CHECK ((released_at IS NULL) = (released_by IS NULL)),
    CHECK (released_by IS NULL OR released_by <> placed_by)
);
CREATE UNIQUE INDEX payout_holds_one_open ON payout_holds (job_id) WHERE released_at IS NULL;
CREATE INDEX payout_holds_open_idx ON payout_holds (placed_at) WHERE released_at IS NULL;

-- Ops overview, last-30-days performance: dispute decisions, swaps and closings by time, without scanning job_events.
CREATE INDEX job_events_ops_perf_idx ON job_events (occurred_at)
    WHERE action IN ('RESOLVE_APPROVE', 'RESOLVE_REPLACE', 'RESOLVE_REFUND', 'APPROVE_SWAP', 'CLOSE');

-- Ops creator search (measured on 100k creators: by city 37 ms with the F3 index, by handle 164 ms on a full scan).
-- Bookable creators by city, in result order (cheapest first), so a page stops after 25 matches.
CREATE INDEX creator_profiles_ops_city_idx ON creator_profiles (lower(city), rate_per_reel_rupees, user_id)
    WHERE completed_at IS NOT NULL AND marketplace_status = 'LISTED' AND review_status = 'APPROVED';
-- Name / handle prefix search (LIKE 'abc%').
CREATE INDEX creator_profiles_handle_prefix_idx ON creator_profiles (lower(instagram_handle) text_pattern_ops);
CREATE INDEX creator_profiles_name_prefix_idx ON creator_profiles (lower(display_name) text_pattern_ops);
