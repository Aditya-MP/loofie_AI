package com.loofie.core.job.domain;

public enum ActorRole {
    BRAND_ADMIN,
    BRAND_MEMBER,
    CREATOR,
    OPS,
    /** Senior ops: may also approve refunds and cancellations (DECISIONS.md section 6). Acts as OPS otherwise. */
    OPS_LEAD,
    /** The founder: second approver for large or repeated refunds, receives the daily summary. Acts as OPS otherwise. */
    FOUNDER,
    SYSTEM;

    public boolean isBrand() {
        return this == BRAND_ADMIN || this == BRAND_MEMBER;
    }

    /** Any ops person. Rules written for OPS apply to all of them. */
    public boolean isOps() {
        return this == OPS || this == OPS_LEAD || this == FOUNDER;
    }

    /** The role used for permission rules: senior ops roles act as OPS. Events keep the real role. */
    public ActorRole acting() {
        return isOps() ? OPS : this;
    }
}
