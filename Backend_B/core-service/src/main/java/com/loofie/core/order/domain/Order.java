package com.loofie.core.order.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Minimal order header for Sprint 1-2. The full aggregate (invoice, payment state, frozen brief) comes in Sprint 3-4. */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;

    @Column(name = "brand_org_id", nullable = false, updatable = false)
    private UUID brandOrgId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Loofie fee and GST the brand was charged, fixed when it paid (basis points: 1500 = 15%). */
    @Column(name = "loofie_fee_bp", nullable = false, updatable = false)
    private int loofieFeeBp;

    @Column(name = "gst_bp", nullable = false, updatable = false)
    private int gstBp;

    @Version
    private Long version;

    protected Order() {
        // for JPA
    }

    public static Order create(UUID id, UUID brandOrgId, Instant now) {
        return create(id, brandOrgId, now, 1500, 1800);
    }

    public static Order create(UUID id, UUID brandOrgId, Instant now, int loofieFeeBp, int gstBp) {
        if (loofieFeeBp < 0 || loofieFeeBp > 10_000 || gstBp < 0 || gstBp > 10_000) {
            throw new IllegalArgumentException("Fee and GST must be between 0 and 10,000 basis points");
        }
        Order o = new Order();
        o.loofieFeeBp = loofieFeeBp;
        o.gstBp = gstBp;
        o.id = Objects.requireNonNull(id);
        o.brandOrgId = Objects.requireNonNull(brandOrgId);
        o.createdAt = now;
        return o;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBrandOrgId() {
        return brandOrgId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public int getLoofieFeeBp() {
        return loofieFeeBp;
    }

    public int getGstBp() {
        return gstBp;
    }
}
