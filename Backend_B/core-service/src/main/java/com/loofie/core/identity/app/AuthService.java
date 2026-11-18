package com.loofie.core.identity.app;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.common.Ids;
import com.loofie.core.identity.domain.Account;
import com.loofie.core.identity.domain.AccountType;
import com.loofie.core.identity.domain.Channel;
import com.loofie.core.identity.domain.Contacts;
import com.loofie.core.identity.domain.IdentityExceptions.AccountSuspendedException;
import com.loofie.core.identity.domain.IdentityExceptions.CodeExpiredException;
import com.loofie.core.identity.domain.IdentityExceptions.ContactTakenException;
import com.loofie.core.identity.domain.IdentityExceptions.InvalidCodeException;
import com.loofie.core.identity.domain.IdentityExceptions.InvalidRequestException;
import com.loofie.core.identity.domain.IdentityExceptions.RateLimitedException;
import com.loofie.core.identity.domain.OtpChallenge;
import com.loofie.core.identity.domain.OtpPurpose;
import com.loofie.core.identity.domain.SessionKind;
import com.loofie.core.identity.domain.SessionView;
import com.loofie.core.identity.domain.Totp;
import com.loofie.core.identity.infra.InviteRepository;
import com.loofie.core.identity.infra.UserRepository;
import com.loofie.core.job.domain.ActorRole;

/**
 * Signup and login (flows 1-4, 6 and 8 on the agreed page). One send / verify pair serves both: a new phone or email
 * gets an account once the person has proved they own it and accepted the terms; an existing one is logged in.
 * Staff always need a second step with their authenticator app.
 */
@Service
public class AuthService {

    public static final int RECOVERY_CODES = 10;

    /** What the screen should do next. */
    public enum Next { DONE, SIGNUP_DETAILS_REQUIRED, MFA_REQUIRED, MFA_ENROL_REQUIRED }

    /**
     * @param session     set when a cookie should be written
     * @param totpSecret  base32, only with MFA_ENROL_REQUIRED (shown once to put into the app)
     * @param recoveryCodes only right after authenticator setup, shown once
     */
    public record Result(Next next, Account account, SessionService.Issued session, AccountType signupType,
                         String totpSecret, String otpauthUri, List<String> recoveryCodes) {

        static Result of(Next next, Account account, SessionService.Issued session) {
            return new Result(next, account, session, null, null, null, List.of());
        }
    }

    public record SignupConsents(boolean terms, boolean adult, boolean marketing) {
    }

    private final OtpService otp;
    private final SessionService sessions;
    private final UserRepository users;
    private final InviteRepository invites;
    private final com.loofie.core.identity.infra.OrganizationRepository organizations;
    private final IdentityCrypto crypto;
    private final RateLimits limits;
    private final AuditLog audit;
    private final IdentityProperties props;
    private final Clock clock;

    public AuthService(OtpService otp, SessionService sessions, UserRepository users, InviteRepository invites,
                       com.loofie.core.identity.infra.OrganizationRepository organizations,
                       IdentityCrypto crypto, RateLimits limits, AuditLog audit, IdentityProperties props,
                       Clock clock) {
        this.otp = otp;
        this.sessions = sessions;
        this.users = users;
        this.invites = invites;
        this.organizations = organizations;
        this.crypto = crypto;
        this.limits = limits;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    public OtpService.Sent sendLoginCode(Channel channel, String rawDestination, String ip) {
        if (channel == null) {
            throw new InvalidRequestException("channel must be PHONE or EMAIL");
        }
        return otp.send(OtpPurpose.LOGIN, channel, Contacts.normalise(channel, rawDestination), null, ip);
    }

    /** Step 2 of every login and signup. A wrong code is counted even though this throws. */
    @Transactional(noRollbackFor = {InvalidCodeException.class, CodeExpiredException.class,
            AccountSuspendedException.class, RateLimitedException.class})
    public Result verifyLogin(UUID challengeId, String code, String ip, String userAgent) {
        limits.verify(ip, clock.instant());
        OtpChallenge c = otp.check(challengeId, code, OtpPurpose.LOGIN, null);
        Account account = users.findByContact(c.channel(), c.destination()).orElse(null);
        if (account == null) {
            // Proved ownership of a new phone / email: ask for the signup details, keep the code usable until expiry.
            return new Result(Next.SIGNUP_DETAILS_REQUIRED, null, null, c.channel().signupType(), null, null,
                    List.of());
        }
        otp.consume(c.id());
        return loginExisting(account, c.channel(), ip, userAgent);
    }

    /** Step 3 for a new phone / email: account type and consents. */
    @Transactional(noRollbackFor = {CodeExpiredException.class})
    public Result signup(UUID challengeId, AccountType type, SignupConsents consents, String ip, String userAgent) {
        OtpChallenge c = otp.requireVerified(challengeId, OtpPurpose.LOGIN);
        if (type == AccountType.ADMIN) {
            throw new InvalidRequestException("Staff accounts are created by invitation only");
        }
        if (type != c.channel().signupType()) {
            throw new InvalidRequestException(c.channel() == Channel.PHONE
                    ? "Phone numbers sign up as creators. Brands sign up with a work email."
                    : "Emails sign up as brands. Creators sign up with a phone number.");
        }
        requireConsents(consents);
        Instant now = clock.instant();
        UUID id = Ids.newId();
        if (!users.insertIfAbsent(id, type, null, c.channel(), c.destination(), now, now, now)) {
            // Someone finished signing up with this phone / email a moment earlier: log into that account.
            Account existing = users.findByContact(c.channel(), c.destination()).orElseThrow();
            otp.consume(c.id());
            return loginExisting(existing, c.channel(), ip, userAgent);
        }
        recordConsents(id, consents, now, ip);
        otp.consume(c.id());
        Account account = users.find(id).orElseThrow();
        return Result.of(Next.DONE, account, sessions.issue(account, SessionKind.FULL, ip, userAgent));
    }

    /**
     * An invite link opened by someone new, after the email OTP. A staff invite creates the staff account and asks for
     * authenticator setup; a brand invite creates a brand account already in the company (flow 5). Someone who already
     * has a brand account accepts while logged in instead ({@link TeamService#acceptAsExistingUser}).
     */
    @Transactional(noRollbackFor = {CodeExpiredException.class})
    public Result acceptInvite(UUID challengeId, String inviteToken, SignupConsents consents, String ip,
                               String userAgent) {
        OtpChallenge c = otp.requireVerified(challengeId, OtpPurpose.LOGIN);
        Instant now = clock.instant();
        InviteRepository.Invite invite = usableInvite(invites, inviteToken, now);
        if (c.channel() != Channel.EMAIL || !invite.email().equals(c.destination())) {
            throw new InvalidRequestException("Log in with the email address the invite was sent to");
        }
        requireConsents(consents);
        boolean staff = invite.orgId() == null;
        UUID id = Ids.newId();
        if (!users.insertIfAbsent(id, staff ? AccountType.ADMIN : AccountType.BRAND,
                staff ? ActorRole.valueOf(invite.role()) : null, Channel.EMAIL, c.destination(), now, now, now)) {
            throw new ContactTakenException(staff
                    ? "This email already has a Loofie account. Use another email for staff."
                    : "This email already has a Loofie account. Log in, then open the invite link again.");
        }
        recordConsents(id, consents, now, ip);
        invites.markAccepted(invite.id(), id, now);
        otp.consume(c.id());
        Map<String, Object> details = Map.of("inviteId", invite.id(), "invitedBy", invite.invitedBy(), "role",
                invite.role());
        if (staff) {
            audit.record(id, invite.role(), AuditLog.STAFF_JOINED, id, null, details);
            return startEnrolment(users.find(id).orElseThrow(), ip, userAgent);
        }
        organizations.addMember(invite.orgId(), id, invite.role(), invite.invitedBy(), now);
        users.markOnboarded(id, now);
        audit.record(id, invite.role(), AuditLog.MEMBER_JOINED, id, invite.orgId(), null, details);
        Account account = users.find(id).orElseThrow();
        return Result.of(Next.DONE, account, sessions.issue(account, SessionKind.FULL, ip, userAgent));
    }

    /** An invite that can still be accepted; the row stays locked until the caller's transaction ends. */
    static InviteRepository.Invite usableInvite(InviteRepository invites, String token, Instant now) {
        if (token == null || token.isBlank()) {
            throw new InvalidRequestException("The invite link is incomplete");
        }
        return invites.findForUpdate(IdentityCrypto.sha256(token.trim())).filter(i -> i.usable(now))
                .orElseThrow(() -> new InvalidRequestException("This invite has expired, was cancelled or was "
                        + "already used. Ask for a new one."));
    }

    /** Staff: first authenticator code after scanning the secret. Gives the recovery codes, once. */
    @Transactional(noRollbackFor = {InvalidCodeException.class, RateLimitedException.class})
    public Result confirmEnrolment(SessionView pending, String code, String ip, String userAgent) {
        requireKind(pending, SessionKind.MFA_ENROL);
        Instant now = clock.instant();
        limits.mfa(pending.userId(), now);
        Account a = users.findForUpdate(pending.userId()).orElseThrow();
        if (a.totpSecretEnc() == null) {
            throw new CodeExpiredException();
        }
        long step = Totp.match(crypto.decryptTotp(a.totpSecretEnc()), code, now).orElseThrow(
                () -> InvalidCodeException.authenticator());
        if (!users.acceptTotpStep(a.id(), step, true, now)) {
            throw InvalidCodeException.authenticator();
        }
        List<String> codes = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        List<byte[]> hmacs = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String rc = crypto.newRecoveryCode();
            codes.add(rc);
            ids.add(Ids.newId());
            hmacs.add(crypto.recoveryHmac(a.id(), rc));
        }
        users.replaceRecoveryCodes(a.id(), ids, hmacs, now);
        audit.record(a.id(), a.staffRole().name(), AuditLog.TOTP_ENROLLED, a.id(), null, Map.of());
        sessions.revoke(pending.sessionId(), "MFA_DONE");
        Account fresh = users.find(a.id()).orElseThrow();
        return new Result(Next.DONE, fresh, sessions.issue(fresh, SessionKind.FULL, ip, userAgent), null, null, null,
                List.copyOf(codes));
    }

    /** Staff: authenticator code (or one recovery code) after the email OTP. */
    @Transactional(noRollbackFor = {InvalidCodeException.class, RateLimitedException.class})
    public Result verifyMfa(SessionView pending, String code, String recoveryCode, String ip, String userAgent) {
        requireKind(pending, SessionKind.MFA_PENDING);
        Instant now = clock.instant();
        limits.mfa(pending.userId(), now);
        Account a = users.findForUpdate(pending.userId()).orElseThrow();
        if (!a.isActive()) {
            throw new AccountSuspendedException();
        }
        if (recoveryCode != null && !recoveryCode.isBlank()) {
            if (!users.useRecoveryCode(a.id(), crypto.recoveryHmac(a.id(), recoveryCode), now)) {
                throw InvalidCodeException.authenticator();
            }
            audit.record(a.id(), a.staffRole().name(), AuditLog.RECOVERY_CODE_USED, a.id(), null,
                    Map.of("unusedLeft", users.unusedRecoveryCodes(a.id())));
        } else {
            if (!a.totpReady()) {
                throw new CodeExpiredException();
            }
            long step = Totp.match(crypto.decryptTotp(a.totpSecretEnc()), code, now)
                    .orElseThrow(() -> InvalidCodeException.authenticator());
            if (!users.acceptTotpStep(a.id(), step, false, now)) {
                throw InvalidCodeException.authenticator(); // the same code twice: wait for the next one
            }
        }
        sessions.revoke(pending.sessionId(), "MFA_DONE");
        return Result.of(Next.DONE, a, sessions.issue(a, SessionKind.FULL, ip, userAgent));
    }

    public void logout(SessionView s) {
        sessions.revoke(s.sessionId(), "LOGOUT");
    }

    public int logoutEverywhere(SessionView s) {
        return sessions.revokeAll(s.userId(), "LOGOUT_ALL");
    }

    public Account account(UUID userId) {
        return users.find(userId).orElseThrow(() -> new com.loofie.core.identity.domain.IdentityExceptions
                .AccountNotFoundException());
    }

    /** A brand user's company and role, if any. */
    public java.util.Optional<com.loofie.core.identity.domain.Organization.Membership> membership(UUID userId) {
        return organizations.membership(userId);
    }

    public String csrfToken(SessionView s) {
        return sessions.csrfToken(s);
    }

    private Result loginExisting(Account account, Channel channel, String ip, String userAgent) {
        if (!account.isActive()) {
            throw new AccountSuspendedException();
        }
        users.markVerified(account.id(), channel, clock.instant());
        if (!account.isStaff()) {
            return Result.of(Next.DONE, account, sessions.issue(account, SessionKind.FULL, ip, userAgent));
        }
        if (account.totpReady()) {
            return Result.of(Next.MFA_REQUIRED, account, sessions.issue(account, SessionKind.MFA_PENDING, ip,
                    userAgent));
        }
        return startEnrolment(account, ip, userAgent); // new staff, or authenticator reset by a senior
    }

    private Result startEnrolment(Account account, String ip, String userAgent) {
        byte[] secret = crypto.newTotpSecret();
        users.setTotpSecret(account.id(), crypto.encryptTotp(secret), clock.instant());
        return new Result(Next.MFA_ENROL_REQUIRED, account, sessions.issue(account, SessionKind.MFA_ENROL, ip,
                userAgent), null, Totp.base32(secret), Totp.otpauthUri(secret, account.email()), List.of());
    }

    private void recordConsents(UUID userId, SignupConsents consents, Instant now, String ip) {
        users.insertConsent(Ids.newId(), userId, "SERVICE", props.noticeVersion(), now, ip);
        if (consents.marketing()) {
            users.insertConsent(Ids.newId(), userId, "MARKETING", props.noticeVersion(), now, ip);
        }
    }

    private static void requireConsents(SignupConsents consents) {
        if (consents == null || !consents.terms()) {
            throw new InvalidRequestException("Accept the terms and privacy notice to continue");
        }
        if (!consents.adult()) {
            throw new InvalidRequestException("Loofie is for people aged 18 or over");
        }
    }

    private static void requireKind(SessionView s, SessionKind kind) {
        if (s == null || s.kind() != kind) {
            throw new com.loofie.core.security.UnauthenticatedException(kind == SessionKind.MFA_ENROL
                    ? "Start again by logging in with your email" : "Log in with your email first");
        }
    }

}
