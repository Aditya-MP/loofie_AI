package com.loofie.core.identity.app;

import java.util.List;
import java.util.UUID;

/**
 * What identity needs to know about a creator's jobs, answered by the job package (so identity never reads job tables).
 * Used to tell ops which jobs a suspended creator leaves open.
 */
public interface CreatorWork {

    /** Jobs where this creator is the active assignee and the job is not finished. */
    List<UUID> activeJobIds(UUID creatorId);
}
