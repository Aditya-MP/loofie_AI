package com.loofie.core.identity.app;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.common.Ids;
import com.loofie.core.identity.domain.Account;
import com.loofie.core.identity.domain.Channel;
import com.loofie.core.identity.domain.Contacts;
import com.loofie.core.identity.domain.IdentityExceptions.CodeExpiredException;
import com.loofie.core.identity.domain.IdentityExceptions.ContactTakenException;
import com.loofie.core.identity.domain.IdentityExceptions.InvalidCodeException;
import com.loofie.core.identity.domain.IdentityExceptions.InvalidRequestException;
import com.loofie.core.identity.domain.OtpChallenge;
import com.loofie.core.identity.domain.OtpPurpose;
import com.loofie.core.identity.domain.SessionKind;
import com.loofie.core.identity.domain.SessionView;
import com.loofie.core.identity.infra.UserRepository;

/** A person's own settings: consents (DPDP) and changing their phone / email with codes to both (Q8). */
@Service
public class AccountService {

    public record ChangeStarted(UUID oldChallengeId, UUID newChallengeId, Instant expiresAt) {
    }

    public record ChangeDone(Account account, SessionService.Issued session) {
    }

    private final UserRepository users;
    private final OtpService otp;
    private final SessionService sessions;
    private final AuditLog audit;
    private final IdentityProperties props;
    private final Clock clock;

    public AccountService(UserRepository users, OtpService otp, SessionService sessions, AuditLog audit,
                          IdentityProperties props, Clock clock) {
        this.users = users;
        this.otp = otp;
        this.sessions = sessions;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    public List<com.loofie.core.identity.domain.Consent> consents(UUID userId) {
        return users.consents(userId);
    }

    /** Marketing consent is opt-in and can be withdrawn as easily as it was given. */
    @Transactional
    public List<com.loofie.core.identity.domain.Consent> setMarketing(UUID userId, boolean granted, String ip) {
        users.findForUpdate(userId).orElseThrow();
        Instant now = clock.instant();
        boolean open = users.consents(userId).stream()
                .anyMatch(c -> c.purpose().equals("MARKETING") && c.withdrawnAt() == null);
        if (granted && !open) {
            users.insertConsent(Ids.newId(), userId, "MARKETING", props.noticeVersion(), now, ip);
        } else if (!granted) {
            users.withdrawConsent(userId, "MARKETING", now);
        }
        return users.consents(userId);
    }

    /** Sends one code to the current phone / email and one to the new one. No transaction: two sends. */
    public ChangeStarted startChange(SessionView s, String rawNew, String ip) {
        Account a = users.find(s.userId()).orElseThrow();
        Channel channel = Channel.loginChannel(a.type());
        String value = Contacts.normalise(channel, rawNew);
        String current = a.loginContact();
        if (value.equals(current)) {
            throw new InvalidRequestException("That is already your " + channel.name().toLowerCase());
        }
        OtpService.Sent old = otp.send(OtpPurpose.CHANGE_OLD, channel, current, a.id(), ip);
        OtpService.Sent fresh = otp.send(OtpPurpose.CHANGE_NEW, channel, value, a.id(), ip);
        return new ChangeStarted(old.challengeId(), fresh.challengeId(), fresh.expiresAt());
    }

    /** Both codes right: the contact changes, every device is logged out, this one gets a fresh session. */
    @Transactional(noRollbackFor = {InvalidCodeException.class, CodeExpiredException.class})
    public ChangeDone confirmChange(SessionView s, UUID oldId, String oldCode, UUID newId, String newCode, String ip,
                                    String userAgent) {
        Account a = users.findForUpdate(s.userId()).orElseThrow();
        Channel channel = Channel.loginChannel(a.type());
        OtpChallenge old = otp.check(oldId, oldCode, OtpPurpose.CHANGE_OLD, a.id());
        OtpChallenge fresh = otp.check(newId, newCode, OtpPurpose.CHANGE_NEW, a.id());
        if (!old.destination().equals(a.loginContact()) || old.channel() != channel || fresh.channel() != channel) {
            throw new CodeExpiredException(); // the contact changed in between: start again
        }
        Instant now = clock.instant();
        try {
            users.changeContact(a.id(), channel, fresh.destination(), now, now);
        } catch (DuplicateKeyException e) {
            throw new ContactTakenException("That " + channel.name().toLowerCase() + " belongs to another account");
        }
        otp.consume(old.id());
        otp.consume(fresh.id());
        int ended = sessions.revokeAll(a.id(), "CONTACT_CHANGED");
        audit.record(a.id(), a.staffRole() != null ? a.staffRole().name() : a.type().name(), AuditLog.CONTACT_CHANGED,
                a.id(), null, Map.of("channel", channel.name(), "from", String.valueOf(Contacts.mask(old.destination())),
                        "to", Contacts.mask(fresh.destination()), "sessionsEnded", ended));
        Account updated = users.find(a.id()).orElseThrow();
        return new ChangeDone(updated, sessions.issue(updated, SessionKind.FULL, ip, userAgent));
    }
}
