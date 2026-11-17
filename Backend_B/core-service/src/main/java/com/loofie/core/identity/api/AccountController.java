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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.loofie.core.identity.app.AccountService;
import com.loofie.core.identity.app.SessionAuthenticator;
import com.loofie.core.identity.domain.IdentityExceptions.InvalidRequestException;
import com.loofie.core.identity.domain.SessionView;

/** The logged-in person's own account: consents and phone / email change. Works for every account type. */
@RestController
@RequestMapping("/api/core/account")
class AccountController {

    record MarketingRequest(Boolean granted) {
    }

    record ChangeRequest(String newContact) {
    }

    record ConfirmRequest(UUID oldChallengeId, String oldCode, UUID newChallengeId, String newCode) {
    }

    record ConfirmResponse(AuthController.UserView user, String csrfToken, Instant sessionExpiresAt) {
    }

    private final AccountService accounts;
    private final SessionAuthenticator authenticator;
    private final SessionCookies cookies;

    AccountController(AccountService accounts, SessionAuthenticator authenticator, SessionCookies cookies) {
        this.accounts = accounts;
        this.authenticator = authenticator;
        this.cookies = cookies;
    }

    @GetMapping("/consents")
    List<com.loofie.core.identity.domain.Consent> consents(HttpServletRequest request) {
        return accounts.consents(authenticator.requireFull(request).userId());
    }

    @PutMapping("/consents/marketing")
    List<com.loofie.core.identity.domain.Consent> marketing(@RequestBody MarketingRequest body, HttpServletRequest request) {
        SessionView s = authenticator.requireFull(request);
        return accounts.setMarketing(s.userId(), body != null && Boolean.TRUE.equals(body.granted()), SessionCookies.ip(request));
    }

    /** Sends a code to the current phone / email and one to the new one. */
    @PostMapping("/contact-change")
    ResponseEntity<AccountService.ChangeStarted> start(@RequestBody ChangeRequest body, HttpServletRequest request) {
        SessionView s = authenticator.requireFull(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accounts.startChange(s,
                body == null ? null : body.newContact(), SessionCookies.ip(request)));
    }

    /** Both codes: the change is made and every other device is logged out. */
    @PostMapping("/contact-change/confirm")
    ConfirmResponse confirm(@RequestBody ConfirmRequest body, HttpServletRequest request,
                            HttpServletResponse response) {
        SessionView s = authenticator.requireFull(request);
        if (body == null || body.oldChallengeId() == null || body.newChallengeId() == null) {
            throw new InvalidRequestException("Both challenge ids and both codes are required");
        }
        AccountService.ChangeDone done = accounts.confirmChange(s, body.oldChallengeId(), body.oldCode(),
                body.newChallengeId(), body.newCode(), SessionCookies.ip(request), SessionCookies.userAgent(request));
        authenticator.clear(request);
        cookies.write(response, done.session());
        return new ConfirmResponse(AuthController.UserView.of(done.account()), done.session().csrfToken(),
                done.session().expiresAt());
    }
}
