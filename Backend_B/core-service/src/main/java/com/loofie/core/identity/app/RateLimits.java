package com.loofie.core.identity.app;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.loofie.core.identity.domain.IdentityExceptions.RateLimitedException;
import com.loofie.core.identity.infra.RateCounterRepository;

/**
 * The agreed limits (2026-11-17): per phone / email 3 codes per 15 minutes and 10 per India day; per IP about 100 per
 * 15 minutes (Jio and Airtel put thousands of people behind one IP, so per-IP stays loose); staff authenticator tries
 * 5 per 15 minutes; a global daily SMS cap. Fixed windows: at a window edge a caller can get up to twice the limit in a
 * few seconds, which is acceptable for these numbers.
 *
 * <p>Known trade-off: anyone can use up a stranger's 3 codes per 15 minutes by asking for codes to their number. The
 * per-IP limit slows this down; the victim waits at most 15 minutes. Recorded in the doc.
 */
@Component
public class RateLimits {

    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** Hits counted by one send, so they can be given back if the message could not be sent. */
    public record Hits(List<String> keys, List<Instant> windows) {
    }

    private final RateCounterRepository counters;
    private final IdentityProperties props;

    public RateLimits(RateCounterRepository counters, IdentityProperties props) {
        this.counters = counters;
        this.props = props;
    }

    /**
     * A per-user allowance in its own window, for features outside login (round C: Instagram status polls, upload
     * tickets, connect links). {@code what} names the counter; the message is shown as is with 429 + Retry-After.
     */
    public void perUser(String what, UUID userId, int limit, Duration window, Instant now, String message) {
        long s = window.toSeconds();
        Instant start = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), s) * s);
        hit(what + ":" + userId, start, limit, now, window, message);
    }

    public Hits otpSend(String destination, String ip, Instant now) {
        hit("otp-ip:" + ip, window(now), props.otpPerIp15m(), now, WINDOW,
                "Too many codes requested from this network. Try again in a few minutes.");
        Hits hits = new Hits(new ArrayList<>(), new ArrayList<>());
        hit("otp-dest:" + destination, window(now), props.otpPerDestination15m(), now, WINDOW,
                "Too many codes sent to this number or email. Try again in a few minutes.");
        hits.keys().add("otp-dest:" + destination);
        hits.windows().add(window(now));
        Instant day = day(now);
        if (!counters.tryHit("otp-dest-day:" + destination, day, props.otpPerDestinationDay())) {
            counters.refund("otp-dest:" + destination, window(now));
            throw new RateLimitedException("Too many codes sent to this number or email today. Try again tomorrow.",
                    Duration.between(now, day.plus(Duration.ofDays(1))));
        }
        hits.keys().add("otp-dest-day:" + destination);
        hits.windows().add(day);
        return hits;
    }

    /** @return false when today's SMS budget is used up */
    public boolean smsBudget(Instant now) {
        return counters.tryHit("sms-day", day(now), props.smsDailyCap());
    }

    public void refundSms(Instant now) {
        counters.refund("sms-day", day(now));
    }

    public void refund(Hits hits) {
        for (int i = 0; i < hits.keys().size(); i++) {
            counters.refund(hits.keys().get(i), hits.windows().get(i));
        }
    }

    public void verify(String ip, Instant now) {
        hit("verify-ip:" + ip, window(now), props.verifyPerIp15m(), now, WINDOW,
                "Too many attempts from this network. Try again in a few minutes.");
    }

    public void mfa(UUID userId, Instant now) {
        hit("mfa-user:" + userId, window(now), props.mfaPerUser15m(), now, WINDOW,
                "Too many authenticator attempts. Try again in 15 minutes or use a recovery code later.");
    }

    private void hit(String key, Instant windowStart, int limit, Instant now, Duration length, String message) {
        if (!counters.tryHit(key, windowStart, limit)) {
            throw new RateLimitedException(message, Duration.between(now, windowStart.plus(length)));
        }
    }

    static Instant window(Instant now) {
        long s = WINDOW.toSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), s) * s);
    }

    static Instant day(Instant now) {
        return now.atZone(INDIA).toLocalDate().atStartOfDay(INDIA).toInstant();
    }
}
