package com.loofie.core.identity.app;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.loofie.core.common.Ids;
import com.loofie.core.identity.domain.Account;
import com.loofie.core.identity.domain.AccountType;
import com.loofie.core.identity.domain.Channel;
import com.loofie.core.identity.domain.Contacts;
import com.loofie.core.identity.domain.IdentityExceptions.AccountNotFoundException;
import com.loofie.core.identity.domain.IdentityExceptions.ContactTakenException;
import com.loofie.core.identity.domain.IdentityExceptions.InvalidRequestException;
import com.loofie.core.identity.domain.MessageSender;
import com.loofie.core.identity.infra.StaffInviteRepository;
import com.loofie.core.identity.infra.UserRepository;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;

/**
 * Staff accounts (flow 6): the first founder from configuration, everyone else by invite; authenticator resets by a
 * senior. No staff account is ever created by editing the database by hand after the first founder.
 */
@Service
public class StaffService {

    private static final Logger log = LoggerFactory.getLogger(StaffService.class);

    /** @param link the invite link; returned to the inviter too, so it can be passed on if the email is slow */
    public record Invite(UUID inviteId, String email, ActorRole role, Instant expiresAt, String link,
                         boolean emailSent) {
    }

    private final UserRepository users;
    private final StaffInviteRepository invites;
    private final SessionService sessions;
    private final AuditLog audit;
    private final IdentityCrypto crypto;
    private final MessageSender sender;
    private final IdentityProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public StaffService(UserRepository users, StaffInviteRepository invites, SessionService sessions, AuditLog audit,
                        IdentityCrypto crypto, MessageSender sender, IdentityProperties props, TransactionTemplate tx,
                        Clock clock) {
        this.users = users;
        this.invites = invites;
        this.sessions = sessions;
        this.audit = audit;
        this.crypto = crypto;
        this.sender = sender;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    public Invite invite(Actor actor, String rawEmail, ActorRole role) {
        if (role == null || !role.isOps()) {
            throw new InvalidRequestException("role must be OPS, OPS_LEAD or FOUNDER");
        }
        StaffRules.requireMayInvite(actor, role);
        String email = Contacts.email(rawEmail);
        String token = crypto.newToken();
        Invite created = tx.execute(status -> {
            if (users.findByContact(Channel.EMAIL, email).isPresent()) {
                throw new ContactTakenException("This email already has a Loofie account");
            }
            Instant now = clock.instant();
            UUID id = Ids.newId();
            Instant expires = now.plus(props.inviteTtl());
            invites.insert(id, email, role, IdentityCrypto.sha256(token), actor.userId(), now, expires);
            audit.record(actor, AuditLog.STAFF_INVITED, null, null, Map.of("inviteId", id, "email",
                    Contacts.mask(email), "role", role.name()));
            return new Invite(id, email, role, expires, props.appBaseUrl() + "/staff/invite#" + token, false);
        });
        boolean sent;
        try {
            sender.send(new MessageSender.Message(Channel.EMAIL, email, "STAFF_INVITE", "You are invited to Loofie",
                    "You have been invited to join Loofie as " + role + ". Open this link within "
                            + props.inviteTtl().toDays() + " days: " + created.link()));
            sent = true;
        } catch (RuntimeException e) {
            log.warn("Staff invite email to {} not sent: {}", Contacts.mask(email), e.toString());
            sent = false;
        }
        return new Invite(created.inviteId(), email, role, created.expiresAt(), created.link(), sent);
    }

    /** Lost phone: the staff member sets up the authenticator again at the next login. All their sessions end. */
    @Transactional
    public void resetAuthenticator(Actor actor, UUID userId, String reason) {
        StaffRules.requireOps(actor);
        String why = StaffRules.requireReason(reason);
        Account target = users.findForUpdate(userId).orElseThrow(AccountNotFoundException::new);
        StaffRules.requireMayResetAuthenticator(actor, target);
        Instant now = clock.instant();
        users.clearTotp(userId, now);
        users.deleteRecoveryCodes(userId);
        int ended = sessions.revokeAll(userId, "TOTP_RESET");
        audit.record(actor, AuditLog.TOTP_RESET, userId, why, Map.of("sessionsEnded", ended));
    }

    /**
     * Creates the first FOUNDER from {@code loofie.identity.bootstrap-founder-email} when no founder has ever existed.
     * Safe with several instances starting at once (advisory lock). Does nothing once any founder exists.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapFounder() {
        String configured = props.bootstrapFounderEmail();
        if (configured == null || configured.isBlank()) {
            return;
        }
        try {
            String email = Contacts.email(configured);
            tx.executeWithoutResult(status -> {
                users.lockFounderBootstrap();
                if (users.anyFounder()) {
                    return;
                }
                if (users.findByContact(Channel.EMAIL, email).isPresent()) {
                    log.error("Founder bootstrap skipped: {} already belongs to a non-founder account",
                            Contacts.mask(email));
                    return;
                }
                Instant now = clock.instant();
                UUID id = Ids.newId();
                users.insertIfAbsent(id, AccountType.ADMIN, ActorRole.FOUNDER, Channel.EMAIL, email, null, null, now);
                audit.record(null, ActorRole.SYSTEM.name(), AuditLog.FOUNDER_BOOTSTRAPPED, id, null,
                        Map.of("email", Contacts.mask(email)));
                log.warn("First founder account created for {}. They log in with an email code and set up an "
                        + "authenticator app. Remove LOOFIE_BOOTSTRAP_FOUNDER_EMAIL now.", Contacts.mask(email));
            });
        } catch (RuntimeException e) {
            log.error("Founder bootstrap failed: {}", e.toString());
        }
    }
}
