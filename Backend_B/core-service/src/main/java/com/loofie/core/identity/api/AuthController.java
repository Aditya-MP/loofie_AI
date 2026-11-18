package com.loofie.core.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.loofie.core.identity.app.AuthService;
import com.loofie.core.identity.app.OtpService;
import com.loofie.core.identity.app.SessionAuthenticator;
import com.loofie.core.identity.app.SessionService;
import com.loofie.core.identity.domain.Account;
import com.loofie.core.identity.domain.AccountType;
import com.loofie.core.identity.domain.Channel;
import com.loofie.core.identity.domain.IdentityExceptions.InvalidRequestException;
import com.loofie.core.identity.domain.Organization;
import com.loofie.core.identity.domain.SessionKind;
import com.loofie.core.identity.domain.SessionView;
import com.loofie.core.security.UnauthenticatedException;

/**
 * Login and signup for everyone (flows 1-4, 6, 8). State-changing calls made with a session cookie need the
 * {@code X-CSRF-Token} header; its value comes back in every response here and from {@code GET /me}.
 */
@RestController
@RequestMapping("/api/core/auth")
class AuthController {

    record SendRequest(Channel channel, String destination) {
    }

    record SendResponse(UUID challengeId, Instant expiresAt, long expiresInSeconds) {
    }

    record VerifyRequest(UUID challengeId, String code) {
    }

    record SignupRequest(UUID challengeId, AccountType accountType, Boolean acceptTerms, Boolean confirmAdult,
                         Boolean marketing) {
    }

    record StaffAcceptRequest(UUID challengeId, String inviteToken, Boolean acceptTerms, Boolean confirmAdult) {
    }

    record MfaRequest(String code, String recoveryCode) {
    }

    /**
     * @param role  CREATOR, a staff role, or BRAND_ADMIN / BRAND_MEMBER; null for a brand user without a company yet
     * @param orgId the brand user's company, or null (then onboardingCompleted is false: show the company form)
     */
    record UserView(UUID id, AccountType accountType, String role, UUID orgId, String status,
                    boolean onboardingCompleted, String phone, String email) {

        static UserView of(Account a, java.util.Optional<Organization.Membership> membership) {
            String role = switch (a.type()) {
                case CREATOR -> "CREATOR";
                case ADMIN -> a.staffRole().name();
                case BRAND -> membership.map(Organization.Membership::role).orElse(null);
            };
            UUID org = a.type() == AccountType.BRAND ? membership.map(Organization.Membership::orgId).orElse(null)
                    : null;
            return new UserView(a.id(), a.type(), role, org, a.status().name(), a.onboardingCompleted(), a.phone(),
                    a.email());
        }
    }

    UserView user(Account a) {
        return UserView.of(a, a.type() == AccountType.BRAND ? auth.membership(a.id()) : java.util.Optional.empty());
    }

    /**
     * @param next          DONE, SIGNUP_DETAILS_REQUIRED, MFA_REQUIRED or MFA_ENROL_REQUIRED
     * @param signupAccountType with SIGNUP_DETAILS_REQUIRED: the type this phone / email signs up as
     * @param totpSecret    with MFA_ENROL_REQUIRED: put into the authenticator app (or scan otpauthUri as a QR code)
     * @param recoveryCodes right after authenticator setup only; shown once, never again
     */
    record LoginResponse(String next, UserView user, String csrfToken, Instant sessionExpiresAt,
                         AccountType signupAccountType, String totpSecret, String otpauthUri,
                         List<String> recoveryCodes) {
    }

    private final AuthService auth;
    private final SessionAuthenticator authenticator;
    private final SessionCookies cookies;

    private final java.time.Clock clock;

    AuthController(AuthService auth, SessionAuthenticator authenticator, SessionCookies cookies,
                   java.time.Clock clock) {
        this.auth = auth;
        this.authenticator = authenticator;
        this.cookies = cookies;
        this.clock = clock;
    }

    /** Same answer whether or not the phone / email has an account. */
    @PostMapping("/otp/send")
    ResponseEntity<SendResponse> send(@RequestBody SendRequest body, HttpServletRequest request) {
        if (body == null) {
            throw new InvalidRequestException("channel and destination are required");
        }
        OtpService.Sent sent = auth.sendLoginCode(body.channel(), body.destination(), SessionCookies.ip(request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new SendResponse(sent.challengeId(), sent.expiresAt(),
                Math.max(0, sent.expiresAt().getEpochSecond() - clock.instant().getEpochSecond())));
    }

    @PostMapping("/otp/verify")
    LoginResponse verify(@RequestBody VerifyRequest body, HttpServletRequest request, HttpServletResponse response) {
        requireChallenge(body == null ? null : body.challengeId());
        return answer(auth.verifyLogin(body.challengeId(), body.code(), SessionCookies.ip(request),
                SessionCookies.userAgent(request)), request, response);
    }

    @PostMapping("/signup")
    LoginResponse signup(@RequestBody SignupRequest body, HttpServletRequest request, HttpServletResponse response) {
        requireChallenge(body == null ? null : body.challengeId());
        return answer(auth.signup(body.challengeId(), body.accountType(), new AuthService.SignupConsents(
                yes(body.acceptTerms()), yes(body.confirmAdult()), yes(body.marketing())), SessionCookies.ip(request),
                SessionCookies.userAgent(request)), request, response);
    }

    /** Staff and brand teammate invites, for someone new (after the email OTP). */
    @PostMapping({"/staff/accept", "/invites/accept"})
    LoginResponse acceptInvite(@RequestBody StaffAcceptRequest body, HttpServletRequest request,
                               HttpServletResponse response) {
        requireChallenge(body == null ? null : body.challengeId());
        return answer(auth.acceptInvite(body.challengeId(), body.inviteToken(), new AuthService.SignupConsents(
                yes(body.acceptTerms()), yes(body.confirmAdult()), false), SessionCookies.ip(request),
                SessionCookies.userAgent(request)), request, response);
    }

    @PostMapping("/mfa/enrol")
    LoginResponse enrol(@RequestBody MfaRequest body, HttpServletRequest request, HttpServletResponse response) {
        SessionView pending = authenticator.current(request).orElseThrow(
                () -> new UnauthenticatedException("Log in with your email first"));
        return answer(auth.confirmEnrolment(pending, body == null ? null : body.code(), SessionCookies.ip(request),
                SessionCookies.userAgent(request)), request, response);
    }

    @PostMapping("/mfa/verify")
    LoginResponse mfa(@RequestBody MfaRequest body, HttpServletRequest request, HttpServletResponse response) {
        SessionView pending = authenticator.current(request).orElseThrow(
                () -> new UnauthenticatedException("Log in with your email first"));
        return answer(auth.verifyMfa(pending, body == null ? null : body.code(),
                body == null ? null : body.recoveryCode(), SessionCookies.ip(request),
                SessionCookies.userAgent(request)), request, response);
    }

    /** Who is logged in, which step is next, and the CSRF token (after a page reload). */
    @GetMapping("/me")
    LoginResponse me(HttpServletRequest request) {
        SessionView s = authenticator.current(request).orElseThrow(() -> new UnauthenticatedException(
                "Log in to continue"));
        String next = switch (s.kind()) {
            case FULL -> "DONE";
            case MFA_PENDING -> "MFA_REQUIRED";
            case MFA_ENROL -> "MFA_ENROL_REQUIRED";
        };
        Account a = auth.account(s.userId());
        return new LoginResponse(next, s.kind() == SessionKind.FULL ? user(a) : null,
                authenticatorCsrf(s), s.expiresAt(), null, null, null, List.of());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authenticator.current(request).ifPresent(auth::logout);
        authenticator.clear(request);
        cookies.clear(response);
        return ResponseEntity.noContent().build();
    }

    /** Ends every session of this account, on every device. */
    @PostMapping("/logout-all")
    ResponseEntity<Void> logoutEverywhere(HttpServletRequest request, HttpServletResponse response) {
        SessionView s = authenticator.current(request).orElseThrow(() -> new UnauthenticatedException(
                "Log in to continue"));
        auth.logoutEverywhere(s);
        authenticator.clear(request);
        cookies.clear(response);
        return ResponseEntity.noContent().build();
    }

    private LoginResponse answer(AuthService.Result r, HttpServletRequest request, HttpServletResponse response) {
        SessionService.Issued issued = r.session();
        if (issued != null) {
            // A login replaces whatever session this browser had.
            authenticator.current(request).filter(old -> !old.sessionId().equals(issued.sessionId()))
                    .ifPresent(auth::logout);
            authenticator.clear(request);
            cookies.write(response, issued);
        }
        boolean full = issued != null && issued.kind() == SessionKind.FULL;
        return new LoginResponse(r.next().name(), full && r.account() != null ? user(r.account()) : null,
                issued == null ? null : issued.csrfToken(), issued == null ? null : issued.expiresAt(),
                r.signupType(), r.totpSecret(), r.otpauthUri(), r.recoveryCodes());
    }

    private String authenticatorCsrf(SessionView s) {
        return auth.csrfToken(s);
    }

    /** A missing tick box is an unticked one. */
    private static boolean yes(Boolean b) {
        return Boolean.TRUE.equals(b);
    }

    private static void requireChallenge(UUID id) {
        if (id == null) {
            throw new InvalidRequestException("challengeId is required");
        }
    }
}
