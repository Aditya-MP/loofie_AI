-- F6 final pass (docs/features/f6-product-delivery.md, "Final pass").

-- P3: production started on its own (courier proof + 24 h, no "Received" tap). The creator's one late-damage report
-- counts 24 h from here, as it does from a "Received" tap. Cleared by a reship and for a new creator.
ALTER TABLE jobs ADD COLUMN auto_started_at timestamptz;
