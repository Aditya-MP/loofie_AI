package com.loofie.core.identity.domain;

import java.time.Instant;
import java.util.UUID;

/** One audit log row: who did what to whom (a person, a company or both), why, from which request. */
public record AuditEntry(UUID id, Instant at, UUID actorId, String actorRole, String action, UUID targetUserId,
                        UUID targetOrgId, String reason, String detailsJson, UUID requestId) {
}
