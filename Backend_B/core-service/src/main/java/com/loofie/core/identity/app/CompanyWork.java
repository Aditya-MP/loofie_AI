package com.loofie.core.identity.app;

import java.util.List;
import java.util.UUID;

/**
 * What identity needs to know about a company's jobs, answered by the job package (identity never reads job tables).
 * Used when the last member of a company deletes their account.
 */
public interface CompanyWork {

    /** Jobs of this company that are not finished (closed, refunded or cancelled). */
    List<UUID> activeJobIds(UUID orgId);
}
