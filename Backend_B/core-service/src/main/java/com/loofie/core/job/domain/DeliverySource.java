package com.loofie.core.job.domain;

/**
 * Where the courier "Delivered" fact came from. It starts the creator's 24h window and then the production
 * clock automatically, so it must always say what it is based on.
 */
public enum DeliverySource {
    /** Ops read the courier's public tracking page. */
    TRACKING_PAGE,
    /** Ops called the courier. */
    COURIER_CALL,
    /** A tracking-service integration reported it (SYSTEM only). */
    TRACKING_API
}
