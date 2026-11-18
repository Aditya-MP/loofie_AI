-- F1 round C part 2: account deletion is confirmed with a fresh code to the person's own phone / email.
ALTER TABLE otp_challenges DROP CONSTRAINT otp_challenges_purpose_check;
ALTER TABLE otp_challenges ADD CONSTRAINT otp_challenges_purpose_check
    CHECK (purpose IN ('LOGIN', 'CHANGE_OLD', 'CHANGE_NEW', 'DELETE'));
-- The storage cleanup finds files to remove without scanning every upload.
CREATE INDEX uploads_delete_requested_idx ON uploads (delete_requested_at)
    WHERE delete_requested_at IS NOT NULL AND deleted_at IS NULL;
