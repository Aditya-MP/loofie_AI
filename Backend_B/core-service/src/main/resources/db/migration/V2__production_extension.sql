-- F9: a creator may extend the production deadline by 48h once, by asking before it passes.
-- Later extensions are ops-only. This flag records that the creator's one free extension is used.
ALTER TABLE jobs ADD COLUMN creator_extension_used boolean NOT NULL DEFAULT false;
