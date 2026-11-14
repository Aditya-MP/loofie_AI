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

    @Version
    private Long version;

    protected Order() {
        // for JPA
    }

    public static Order create(UUID id, UUID brandOrgId, Instant now) {
        Order o = new Order();
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
}
