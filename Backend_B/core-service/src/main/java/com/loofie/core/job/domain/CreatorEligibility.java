package com.loofie.core.job.domain;

import java.util.UUID;

/**
 * Whether a creator may take work (F1 round C, decision C6): account active, passport complete, listed. Answered by
 * the creator package, so the job engine never reads creator or identity tables.
 */
public interface CreatorEligibility {

    boolean mayAcceptWork(UUID creatorId);
}
