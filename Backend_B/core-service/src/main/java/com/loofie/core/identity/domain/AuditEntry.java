package com.loofie.core.identity.domain;

import java.time.Instant;
import java.util.UUID;

/** One audit log row: who did what to whom, why, from which request. */
public record AuditEntry(UUID id, Instant at, UUID actorId, String actorRole, String action, UUID targetUserId,
                         String reason, String detailsJson, UUID requestId) {
}
