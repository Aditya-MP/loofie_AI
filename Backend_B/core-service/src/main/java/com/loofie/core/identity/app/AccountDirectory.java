package com.loofie.core.identity.app;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.loofie.core.identity.domain.AccountStatus;
import com.loofie.core.identity.infra.UserRepository;

/**
 * What other features may ask of or tell identity about an account, without touching its tables (so identity can
 * become its own service later). Used by the creator passport (round C).
 */
@Service
public class AccountDirectory {

    private final UserRepository users;
    private final Clock clock;

    public AccountDirectory(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    /** The account exists and may log in (not suspended or deleted). */
    public boolean active(UUID userId) {
        return users.find(userId).map(a -> a.status() == AccountStatus.ACTIVE).orElse(false);
    }

    /** Sets the onboarding flag {@code /auth/me} reports, so the app knows whether to show the form or the dashboard. */
    public void setOnboarded(UUID userId, boolean done) {
        if (done) {
            users.markOnboarded(userId, clock.instant());
        } else {
            users.markNotOnboarded(userId, clock.instant());
        }
    }
}
