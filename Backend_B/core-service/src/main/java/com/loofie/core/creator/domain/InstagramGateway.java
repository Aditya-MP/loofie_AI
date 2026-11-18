package com.loofie.core.creator.domain;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Backend A's Instagram side (their docs/API.md, "Instagram Login"), server to server with our API key. The account
 * ref is always our creator id. Answers {@link com.loofie.core.common.DependencyUnavailableException} when Backend A is
 * down or not configured.
 */
public interface InstagramGateway {

    /** The 10-minute link that sends her to Meta's screen. Ask again for every tap. */
    LoginLink loginLink(String accountRef);

    /** The connection as Backend A sees it, or empty if she never connected. The only proof of a connection. */
    Optional<Connection> connection(String accountRef);

    /** Numbers Backend A verified from Instagram, or empty if it has none for this handle yet. */
    Optional<Verified> verifiedNumbers(String handle);

    /** Forget the connection; Backend A keeps the fetched data (the delete-with-data call goes through the outbox). */
    void disconnect(String accountRef);

    record LoginLink(URI url, Instant expiresAt) {
    }

    /** @param status Backend A's word: syncing, active, error, expired, revoked */
    record Connection(String handle, String status, boolean insightsAllowed, Instant tokenExpiresAt,
                      Instant lastSyncedAt, String lastError) {
    }

    record Verified(Long followers, LocalDate followersCapturedOn, Long medianViews, LocalDate viewsCapturedOn) {
    }
}
