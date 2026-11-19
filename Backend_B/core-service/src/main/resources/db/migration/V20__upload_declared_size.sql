-- Upload size limit at the storage (open ask 45): every upload link is signed for one exact size, declared by the
-- app when it asks for the link. The store refuses any other size; attach checks it again.
-- Null for keys issued before this change (they keep the old "at most max_bytes" check until they expire).
ALTER TABLE uploads ADD COLUMN declared_bytes bigint CHECK (declared_bytes IS NULL OR declared_bytes > 0);
