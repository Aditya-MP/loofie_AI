package com.loofie.core.identity.app;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.loofie.core.common.Ids;
import com.loofie.core.identity.domain.Channel;
import com.loofie.core.identity.domain.Contacts;
import com.loofie.core.identity.domain.IdentityExceptions.CodeExpiredException;
import com.loofie.core.identity.domain.IdentityExceptions.DeliveryUnavailableException;
import com.loofie.core.identity.domain.IdentityExceptions.InvalidCodeException;
import com.loofie.core.identity.domain.MessageSender;
import com.loofie.core.identity.domain.OtpChallenge;
import com.loofie.core.identity.domain.OtpPurpose;
import com.loofie.core.identity.infra.OtpRepository;
import com.loofie.core.notification.app.NotificationService;

/**
 * Sends and checks one-time codes. Sending answers the same whether or not the phone / email has an account, so
 * nobody can find out who uses Loofie. The SMS / email goes out after the challenge row is saved and outside any
 * transaction; if it cannot be sent, the challenge is marked failed and the person's allowance is given back.
 */
@Service
public class OtpService {

    /** One template for every purpose: fewer DLT templates to register. */
    static final String SMS_TEXT = "%s is your Loofie verification code. It expires in %d minutes. Do not share it "
            + "with anyone.";
    public static final String SMS_CAP_NOTICE = "SMS_DAILY_CAP_REACHED";

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    public record Sent(UUID challengeId, Instant expiresAt) {
    }

    private final OtpRepository otps;
    private final RateLimits limits;
    private final MessageSender sender;
    private final IdentityCrypto crypto;
    private final IdentityProperties props;
    private final NotificationService notifications;
    private final Clock clock;

    public OtpService(OtpRepository otps, RateLimits limits, MessageSender sender, IdentityCrypto crypto,
                      IdentityProperties props, NotificationService notifications, Clock clock) {
        this.otps = otps;
        this.limits = limits;
        this.sender = sender;
        this.crypto = crypto;
        this.props = props;
        this.notifications = notifications;
        this.clock = clock;
    }

    /** @param destination already normalised ({@link Contacts}) */
    public Sent send(OtpPurpose purpose, Channel channel, String destination, UUID userId, String ip) {
        Instant now = clock.instant();
        RateLimits.Hits hits = limits.otpSend(destination, ip, now);
        boolean smsCounted = false;
        if (channel == Channel.PHONE) {
            if (!limits.smsBudget(now)) {
                limits.refund(hits);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("cap", props.smsDailyCap());
                payload.put("action", "Raise loofie.identity.sms-daily-cap if this is real traffic; otherwise "
                        + "look for a bot (SMS pumping).");
                notifications.toOpsOncePerDay(SMS_CAP_NOTICE, payload, now);
                log.warn("SMS daily cap ({}) reached: OTP texts are paused until midnight India time",
                        props.smsDailyCap());
                throw new DeliveryUnavailableException("Text messages are paused for today. Try again tomorrow or "
                        + "contact support.");
            }
            smsCounted = true;
        }
        UUID id = Ids.newId();
        String code = crypto.newOtp();
        Instant expires = now.plus(props.otpTtl());
        otps.insert(new OtpChallenge(id, purpose, channel, destination, userId, crypto.otpHmac(id, code), 0, now,
                expires, null, null, null), ip);
        try {
            sender.send(new MessageSender.Message(channel, destination, "OTP", "Your Loofie code",
                    SMS_TEXT.formatted(code, props.otpTtl().toMinutes())));
        } catch (RuntimeException e) {
            otps.markFailed(id, clock.instant());
            limits.refund(hits);
            if (smsCounted) {
                limits.refundSms(now);
            }
            log.warn("OTP {} to {} could not be sent: {}", purpose, Contacts.mask(destination), e.toString());
            throw e instanceof DeliveryUnavailableException d ? d
                    : new DeliveryUnavailableException("The code could not be sent. Try again in a minute.");
        }
        return new Sent(id, expires);
    }

    /**
     * Checks a code inside the caller's transaction. A wrong code is counted (the caller's transaction must not roll
     * back on {@link InvalidCodeException} / {@link CodeExpiredException}); the right one marks the challenge verified.
     * The caller consumes it when the login or change is done.
     */
    public OtpChallenge check(UUID challengeId, String code, OtpPurpose purpose, UUID userId) {
        requireTransaction();
        Instant now = clock.instant();
        OtpChallenge c = otps.findForUpdate(challengeId).orElseThrow(CodeExpiredException::new);
        if (c.purpose() != purpose || (userId != null && !userId.equals(c.userId())) || !c.open(now,
                props.otpMaxAttempts())) {
            throw new CodeExpiredException();
        }
        if (code == null || !IdentityCrypto.same(crypto.otpHmac(c.id(), code.trim()), c.codeHmac())) {
            otps.wrongAttempt(c.id());
            throw new InvalidCodeException(props.otpMaxAttempts() - c.attempts() - 1);
        }
        otps.markVerified(c.id(), now);
        return c;
    }

    /** A challenge whose code was already checked and that is still unused (the signup step after verify). */
    public OtpChallenge requireVerified(UUID challengeId, OtpPurpose purpose) {
        requireTransaction();
        OtpChallenge c = otps.findForUpdate(challengeId).orElseThrow(CodeExpiredException::new);
        if (c.purpose() != purpose || c.verifiedAt() == null || c.consumedAt() != null
                || !clock.instant().isBefore(c.expiresAt())) {
            throw new CodeExpiredException();
        }
        return c;
    }

    public void consume(UUID challengeId) {
        otps.consume(challengeId, clock.instant());
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OTP checks must run inside the caller's transaction");
        }
    }
}
