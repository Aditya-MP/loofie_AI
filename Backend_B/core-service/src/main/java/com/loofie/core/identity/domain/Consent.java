package com.loofie.core.identity.domain;

import java.time.Instant;
import java.util.UUID;

/** One consent given (SERVICE or MARKETING) under one notice version; withdrawn when withdrawnAt is set. */
public record Consent(UUID id, String purpose, String noticeVersion, Instant grantedAt, Instant withdrawnAt) {
}
