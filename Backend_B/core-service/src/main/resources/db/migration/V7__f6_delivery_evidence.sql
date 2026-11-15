-- F6 plan items 1 and 2 (docs/features/f6-product-delivery.md). Expand-only: the previous app version
-- never writes these columns and never writes receipt photos in another format in production.

-- Item 2: "Delivered" records the courier's delivery time (in courier_delivered_at, as before) plus what
-- it is based on and when we were told. The creator's 24h window counts from the delivery time.
ALTER TABLE shipments
    ADD COLUMN courier_delivered_source      varchar(16)
        CHECK (courier_delivered_source IN ('TRACKING_PAGE', 'COURIER_CALL', 'TRACKING_API')),
    ADD COLUMN courier_delivered_recorded_at timestamptz,
    ADD CONSTRAINT shipments_delivered_after_shipped CHECK (courier_delivered_at IS NULL OR courier_delivered_at >= shipped_at);

-- Item 1: a receipt photo is only ever a storage key we issue, never a URL or free text (ops opens it).
-- Same pattern as Shipment.RECEIPT_PHOTO_KEY.
ALTER TABLE shipments ADD CONSTRAINT shipments_receipt_photo_key CHECK (
    receipt_photo_ref IS NULL OR receipt_photo_ref ~
        '^receipts/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|jpeg|png|heic|webp)$');
