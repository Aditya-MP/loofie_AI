package com.loofie.core.job.domain;

import java.util.UUID;

/**
 * What the job engine needs from a brand's company profile (F1 round B), answered by identity. Like
 * {@link WorkingCalendar}, a port: the engine never reads identity tables.
 */
public interface BrandProfiles {

    /** The brand's pickup state code, or null when unknown (only national holidays then count). */
    String pickupState(UUID brandOrgId);
}
