package com.loofie.core.identity.app;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.loofie.core.identity.domain.Account;
import com.loofie.core.identity.domain.AccountStatus;
import com.loofie.core.identity.domain.AccountType;
import com.loofie.core.identity.domain.Channel;
import com.loofie.core.identity.domain.Contacts;
import com.loofie.core.identity.domain.IdentityExceptions.AccountNotFoundException;
import com.loofie.core.identity.domain.IdentityExceptions.ContactTakenException;
import com.loofie.core.identity.domain.IdentityExceptions.InvalidRequestException;
import com.loofie.core.identity.domain.MessageSender;
import com.loofie.core.identity.infra.UserRepository;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.notification.app.NotificationService;

/** Ops tools on accounts (flow 9): suspend, unsuspend, contact change after a manual check, account view, audit log. */
@Service
public class AccountAdminService {

    public static final String SUSPENDED_NOTICE = "ACCOUNT_SUSPENDED";
    static final int MAX_AUDIT_PAGE = 200;
    private static final Logger log = LoggerFactory.getLogger(AccountAdminService.class);

    /** What ops sees: contacts masked, nothing about the authenticator. */
    public record AccountSummary(UUID id, AccountType type, AccountStatus status, ActorRole staffRole,
                                 String phoneMasked, String emailMasked, boolean phoneVerified, boolean emailVerified,
                                 boolean onboardingCompleted, String statusReason, Instant createdAt) {
    }

    private final UserRepository users;
    private final SessionService sessions;
    private final AuditLog audit;
    private final NotificationService notifications;
    private final CreatorWork creatorWork;
    private final MessageSender sender;
    private final Clock clock;

    public AccountAdminService(UserRepository users, SessionService sessions, AuditLog audit,
                               NotificationService notifications, CreatorWork creatorWork, MessageSender sender,
                               Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.audit = audit;
        this.notifications = notifications;
        this.creatorWork = creatorWork;
        this.sender = sender;
        this.clock = clock;
    }

    /**
     * Blocks the account at once: every session ends in this transaction and the next request is refused. Ops gets
     * the creator's open jobs to decide on (they stay as they are, Q11); the person gets a message they can reply to.
     */
    @Transactional
    public AccountSummary suspend(Actor actor, UUID userId, String reason) {
        StaffRules.requireOps(actor); // who you are before what you sent
        String why = StaffRules.requireReason(reason);
        Account target = users.findForUpdate(userId).orElseThrow(AccountNotFoundException::new);
        StaffRules.requireMayManage(actor, target);
        if (target.status() != AccountStatus.ACTIVE) {
            throw new InvalidRequestException("This account is not active");
        }
        if (target.staffRole() == ActorRole.FOUNDER && users.activeFounders() <= 1) {
            throw new ActorNotAllowedException("The last active founder cannot be suspended");
        }
        Instant now = clock.instant();
        users.setStatus(userId, AccountStatus.SUSPENDED, why, actor.userId(), now);
        int ended = sessions.revokeAll(userId, "SUSPENDED");
        List<UUID> openJobs = target.type() == AccountType.CREATOR ? creatorWork.activeJobIds(userId) : List.of();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sessionsEnded", ended);
        details.put("openJobs", openJobs.size());
        audit.record(actor, AuditLog.SUSPENDED, userId, why, details);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("accountType", target.type().name());
        payload.put("reason", why);
        payload.put("suspendedBy", actor.userId());
        payload.put("openJobIds", openJobs);
        payload.put("action", openJobs.isEmpty() ? "None needed" : "Decide each open job: reassign or cancel");
        notifications.toOps(SUSPENDED_NOTICE, null, payload, now);
        afterCommit(() -> tell(target, "ACCOUNT_SUSPENDED", "Your Loofie account is suspended",
                "Your Loofie account has been suspended. Reason: " + why + ". To appeal, reply to this message."));
        return summary(users.find(userId).orElseThrow());
    }

    @Transactional
    public AccountSummary unsuspend(Actor actor, UUID userId, String reason) {
        StaffRules.requireOps(actor);
        String why = StaffRules.requireReason(reason);
        Account target = users.findForUpdate(userId).orElseThrow(AccountNotFoundException::new);
        StaffRules.requireMayManage(actor, target);
        if (target.status() != AccountStatus.SUSPENDED) {
            throw new InvalidRequestException("This account is not suspended");
        }
        users.setStatus(userId, AccountStatus.ACTIVE, null, actor.userId(), clock.instant());
        audit.record(actor, AuditLog.UNSUSPENDED, userId, why, Map.of());
        return summary(users.find(userId).orElseThrow());
    }

    /**
     * The person lost their old phone / email and ops checked who they are by hand (Q8). The new one is unverified
     * until they log in with a code sent to it; every session ends.
     */
    @Transactional
    public AccountSummary changeContact(Actor actor, UUID userId, String rawNew, String reason) {
        StaffRules.requireOps(actor);
        String why = StaffRules.requireReason(reason);
        Account target = users.findForUpdate(userId).orElseThrow(AccountNotFoundException::new);
        StaffRules.requireMayManage(actor, target);
        Channel channel = Channel.loginChannel(target.type());
        String value = Contacts.normalise(channel, rawNew);
        String old = target.loginContact();
        if (value.equals(old)) {
            throw new InvalidRequestException("That is already this account's " + channel.name().toLowerCase());
        }
        Instant now = clock.instant();
        try {
            users.changeContact(userId, channel, value, null, now);
        } catch (DuplicateKeyException e) {
            throw new ContactTakenException("That " + channel.name().toLowerCase() + " belongs to another account");
        }
        int ended = sessions.revokeAll(userId, "CONTACT_CHANGED");
        audit.record(actor, AuditLog.CONTACT_CHANGED_BY_OPS, userId, why, Map.of("channel", channel.name(),
                "from", String.valueOf(Contacts.mask(old)), "to", Contacts.mask(value), "sessionsEnded", ended));
        return summary(users.find(userId).orElseThrow());
    }

    /** Opening someone's account is itself recorded. */
    @Transactional
    public AccountSummary view(Actor actor, UUID userId) {
        StaffRules.requireOps(actor);
        Account a = users.find(userId).orElseThrow(AccountNotFoundException::new);
        audit.record(actor, AuditLog.VIEWED, userId, null, Map.of());
        return summary(a);
    }

    public List<com.loofie.core.identity.domain.AuditEntry> auditLog(Actor actor, UUID target, Instant before, int limit) {
        StaffRules.requireSenior(actor);
        int n = Math.max(1, Math.min(limit <= 0 ? 50 : limit, MAX_AUDIT_PAGE));
        return audit.list(target, before == null ? clock.instant().plusSeconds(1) : before, n);
    }

    static AccountSummary summary(Account a) {
        return new AccountSummary(a.id(), a.type(), a.status(), a.staffRole(), Contacts.mask(a.phone()),
                Contacts.mask(a.email()), a.phoneVerifiedAt() != null, a.emailVerifiedAt() != null,
                a.onboardingCompleted(), a.statusReason(), a.createdAt());
    }

    /** Email when the account has one (people can reply to appeal), otherwise SMS. Best effort, never blocks ops. */
    private void tell(Account a, String kind, String subject, String text) {
        Channel channel = a.email() != null ? Channel.EMAIL : Channel.PHONE;
        String to = channel == Channel.EMAIL ? a.email() : a.phone();
        try {
            sender.send(new MessageSender.Message(channel, to, kind, subject, text));
        } catch (RuntimeException e) {
            log.warn("{} message to {} not sent: {}", kind, Contacts.mask(to), e.toString());
        }
    }

    private static void afterCommit(Runnable r) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                r.run();
            }
        });
    }
}
