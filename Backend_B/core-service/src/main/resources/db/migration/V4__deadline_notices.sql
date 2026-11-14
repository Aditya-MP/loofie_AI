-- Timer worker: one row per reminder/alert sent for a job deadline, so a notice is sent exactly once
-- even across restarts and several running instances. due_at is part of the key: when a deadline
-- moves (e.g. an extension), the reminder for the new time is sent again.
CREATE TABLE deadline_notices (
    job_id      uuid        NOT NULL REFERENCES jobs (id),
    kind        varchar(40) NOT NULL,
    due_at      timestamptz NOT NULL,
    event_id    uuid        NOT NULL,           -- the outbox event that carried the notice
    sent_at     timestamptz NOT NULL,
    PRIMARY KEY (job_id, kind, due_at)
);
