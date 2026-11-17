package com.loofie.core.identity.domain;

import java.time.Instant;
import java.util.UUID;

/** One code sent. Only an HMAC of the code is kept. */
public record OtpChallenge(UUID id, OtpPurpose purpose, Channel channel, String destination, UUID userId,
                           byte[] codeHmac, int attempts, Instant createdAt, Instant expiresAt, Instant verifiedAt,
                           Instant consumedAt, Instant failedAt) {

    /** Can still be checked: not expired, used, failed or out of tries. */
    public boolean open(Instant now, int maxAttempts) {
        return consumedAt == null && failedAt == null && now.isBefore(expiresAt) && attempts < maxAttempts;
    }
}
