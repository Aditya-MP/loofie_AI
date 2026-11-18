package com.loofie.core.creator.app;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.loofie.core.creator.domain.CreatorProfile;
import com.loofie.core.creator.domain.CreatorProfile.InstagramStatus;
import com.loofie.core.creator.domain.InstagramGateway;
import com.loofie.core.creator.infra.CreatorProfileRepository;
import com.loofie.core.identity.app.AuditLog;
import com.loofie.core.identity.app.RateLimits;
import com.loofie.core.job.domain.Actor;

/**
 * "Continue with Instagram" (flow 1, phone first: she is already a logged-in creator). Backend A runs Meta's login and
 * the fetch; we only hand out its link and ask it, server to server, what happened. <b>The return URL is never
 * proof</b> ({@code ?instagram=connected} is plain text anyone can type): only {@link InstagramGateway#connection}
 * changes what we store.
 */
@Service
public class InstagramConnectService {

    static final int LINKS_PER_15_MIN = 10;
    /** The app polls every 2 s while "Fetching your numbers…"; 40 per minute leaves room for a reload or two. */
    static final int POLLS_PER_MINUTE = 40;

    public record Link(URI url, Instant expiresAt) {
    }

    /**
     * @param message what to tell her, by status / failure reason (the app may use its own words)
     */
    public record Status(InstagramStatus status, String handle, Boolean insightsAllowed, boolean profileCompleted,
                         String message) {
    }

    private final InstagramGateway gateway;
    private final CreatorProfileRepository profiles;
    private final CreatorProfileService profileService;
    private final AuditLog audit;
    private final RateLimits limits;
    private final TransactionTemplate tx;
    private final Clock clock;

    public InstagramConnectService(InstagramGateway gateway, CreatorProfileRepository profiles,
                                   CreatorProfileService profileService, AuditLog audit, RateLimits limits,
                                   TransactionTemplate tx, Clock clock) {
        this.gateway = gateway;
        this.profiles = profiles;
        this.profileService = profileService;
        this.audit = audit;
        this.limits = limits;
        this.tx = tx;
        this.clock = clock;
    }

    /** Backend A's 10-minute link to Meta's screen, for her own account (the ref is always her creator id). */
    public Link start(Actor actor) {
        UUID me = CreatorProfileService.requireCreator(actor);
        limits.perUser("ig-link", me, LINKS_PER_15_MIN, Duration.ofMinutes(15), clock.instant(),
                "Too many Instagram attempts. Try again in 15 minutes, or fill in your numbers by hand.");
        profiles.insertIfAbsent(me, clock.instant());
        InstagramGateway.LoginLink link = gateway.loginLink(me.toString());
        return new Link(link.url(), link.expiresAt());
    }

    /**
     * Asks Backend A where her connection stands and stores it. Called by the app after Meta sends her back, then
     * every 2 s while the numbers are fetched.
     */
    public Status status(Actor actor) {
        UUID me = CreatorProfileService.requireCreator(actor);
        limits.perUser("ig-poll", me, POLLS_PER_MINUTE, Duration.ofMinutes(1), clock.instant(),
                "Checking too often. Wait a few seconds.");
        profiles.insertIfAbsent(me, clock.instant());
        Optional<InstagramGateway.Connection> c = gateway.connection(me.toString()); // outside the transaction
        return tx.execute(s -> {
            CreatorProfile before = profiles.findForUpdate(me).orElseThrow();
            Instant now = clock.instant();
            if (c.isEmpty()) {
                return new Status(before.instagramStatus(), before.instagramStatus() == InstagramStatus.NOT_CONNECTED
                        ? null : before.instagramHandle(), before.instagramInsightsAllowed(), before.completed(),
                        message(before.instagramStatus(), null));
            }
            InstagramStatus st = Optional.ofNullable(InstagramStatus.fromBackendA(c.get().status()))
                    .orElse(InstagramStatus.SYNCING); // "connected" and anything new: still being fetched
            profiles.setInstagram(me, st, c.get().handle(), c.get().insightsAllowed(), now);
            if (st == InstagramStatus.ACTIVE && before.instagramStatus() != InstagramStatus.ACTIVE) {
                audit.record(actor, AuditLog.INSTAGRAM_CONNECTED, me, null, Map.of("handle",
                        String.valueOf(c.get().handle())));
            }
            boolean changed = st != before.instagramStatus()
                    || !java.util.Objects.equals(c.get().handle(), before.instagramHandle())
                    || !java.util.Objects.equals(c.get().insightsAllowed(), before.instagramInsightsAllowed());
            profileService.settle(me, actor, changed);
            CreatorProfile after = profiles.find(me).orElseThrow();
            return new Status(st, c.get().handle(), c.get().insightsAllowed(), after.completed(),
                    message(st, c.get().insightsAllowed()));
        });
    }

    /** She disconnects. Backend A keeps what it fetched (that is deleted only with her account). */
    public Status disconnect(Actor actor) {
        UUID me = CreatorProfileService.requireCreator(actor);
        gateway.disconnect(me.toString());
        return tx.execute(s -> {
            CreatorProfile p = profiles.findForUpdate(me).orElseThrow();
            profiles.setInstagram(me, InstagramStatus.NOT_CONNECTED, null, null, clock.instant());
            audit.record(actor, AuditLog.INSTAGRAM_DISCONNECTED, me, null, Map.of());
            return new Status(InstagramStatus.NOT_CONNECTED, p.instagramHandle(), null, p.completed(),
                    "Instagram disconnected. Your numbers now show as self-reported until you reconnect.");
        });
    }

    static String message(InstagramStatus status, Boolean insightsAllowed) {
        return switch (status) {
            case NOT_CONNECTED -> "Not connected yet";
            case SYNCING -> "Fetching your numbers…";
            case ACTIVE -> Boolean.FALSE.equals(insightsAllowed)
                    ? "Connected. Views could not be read: reconnect and allow views and reach"
                    : "Instagram connected";
            case ERROR -> "Instagram is slow right now; we will keep trying";
            case EXPIRED, REVOKED -> "Your Instagram login expired. Reconnect Instagram";
        };
    }
}
