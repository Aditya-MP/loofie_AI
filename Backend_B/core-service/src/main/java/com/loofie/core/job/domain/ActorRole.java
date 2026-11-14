package com.loofie.core.job.domain;

public enum ActorRole {
    BRAND_ADMIN,
    BRAND_MEMBER,
    CREATOR,
    OPS,
    SYSTEM;

    public boolean isBrand() {
        return this == BRAND_ADMIN || this == BRAND_MEMBER;
    }
}
