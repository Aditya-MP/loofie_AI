package com.loofie.core.identity.app;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.identity.domain.Account;
import com.loofie.core.identity.domain.AccountStatus;
import com.loofie.core.identity.domain.AccountType;
import com.loofie.core.identity.domain.Channel;
import com.loofie.core.identity.domain.Contacts;
import com.loofie.core.identity.domain.IdentityExceptions.AccountNotFoundException;
import com.loofie.core.identity.domain.IdentityExceptions.CodeExpiredException;
import com.loofie.core.identity.domain.IdentityExceptions.DeletionBlockedException;
import com.loofie.core.identity.domain.IdentityExceptions.InvalidCodeException;
import com.loofie.core.identity.domain.IdentityExceptions.InvalidRequestException;
import com.loofie.core.identity.domain.Organization;
import com.loofie.core.identity.domain.OtpChallenge;
import com.loofie.core.identity.domain.OtpPurpose;
import com.loofie.core.identity.domain.SessionView;
import com.loofie.core.identity.infra.OrganizationRepository;
import com.loofie.core.identity.infra.UserRepository;
import com.loofie.core.job.domain.Actor;

/**
 * Account deletion (F1 round C part 2, DPDP Act). The person asks, a fresh code to her own phone / email confirms it
 * (decision C7: a stolen session alone cannot delete an account), then in <b>one transaction</b>: status DELETED,
 * phone / email / authenticator cleared (the number can sign up again), every session ended, marketing consent
 * withdrawn, company membership removed, every {@link PersonalDataHolder} erased (passport, uploaded files queued for
 * deletion, Instagram data queued for Backend A). Jobs, events, consents and the audit log keep the user id, which is
 * not personal data, so business and legal records stay intact.
 *
 * <p>Refused (409, with the reason) while she has an unfinished job (as a creator, which includes payouts not yet
 * closed), or when she is the only admin of a team with other members, or the last member of a company with
 * unfinished jobs. Staff accounts are closed by suspension, not deleted here.
 */
@Service
public class AccountDeletionService {

    public static final String REASON_SELF = "Deleted by the account holder";

    public record Started(UUID challengeId, Instant expiresAt, String sentTo) {
    }

    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final OtpService otp;
    private final SessionService sessions;
    private final AuditLog audit;
    private final CreatorWork creatorWork;
    private final CompanyWork companyWork;
    private final List<PersonalDataHolder> holders;
    private final Clock clock;

    public AccountDeletionService(UserRepository users, OrganizationRepository organizations, OtpService otp,
                                  SessionService sessions, AuditLog audit, CreatorWork creatorWork,
                                  CompanyWork companyWork, List<PersonalDataHolder> holders, Clock clock) {
        this.users = users;
        this.organizations = organizations;
        this.otp = otp;
        this.sessions = sessions;
        this.audit = audit;
        this.creatorWork = creatorWork;
        this.companyWork = companyWork;
        this.holders = holders;
        this.clock = clock;
    }

    /** Checks what would block the deletion, then sends a code to her own phone / email. No transaction: a send. */
    public Started start(SessionView s, String ip) {
        Account a = users.find(s.userId()).orElseThrow(AccountNotFoundException::new);
        requireDeletable(a);
        checkBlockers(a, false);
        Channel channel = Channel.loginChannel(a.type());
        OtpService.Sent sent = otp.send(OtpPurpose.DELETE, channel, a.loginContact(), a.id(), ip);
        return new Started(sent.challengeId(), sent.expiresAt(), Contacts.mask(a.loginContact()));
    }

    /** The right code: the account is deleted. A wrong code is counted and nothing else happens. */
    @Transactional(noRollbackFor = {InvalidCodeException.class, CodeExpiredException.class})
    public void confirm(SessionView s, UUID challengeId, String code) {
        if (challengeId == null) {
            throw new InvalidRequestException("challengeId and code are required");
        }
        Account a = users.findForUpdate(s.userId()).orElseThrow(AccountNotFoundException::new);
        requireDeletable(a);
        OtpChallenge c = otp.check(challengeId, code, OtpPurpose.DELETE, a.id());
        if (!c.destination().equals(a.loginContact())) {
            throw new CodeExpiredException(); // her contact changed in between: start again
        }
        otp.consume(c.id());
        checkBlockers(a, true);
        erase(a, a.id(), a.type().name(), REASON_SELF, AuditLog.ACCOUNT_DELETED);
    }

    /** A founder or ops lead deletes an account on a grievance request (reason required, audited). Same blockers. */
    @Transactional
    public void deleteByOps(Actor actor, UUID userId, String reason) {
        StaffRules.requireSenior(actor);
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new InvalidRequestException("A reason (up to 500 characters) is required; it is kept in the audit log");
        }
        Account a = users.findForUpdate(userId).orElseThrow(AccountNotFoundException::new);
        requireDeletable(a);
        checkBlockers(a, true);
        erase(a, actor.userId(), actor.role().name(), reason.trim(), AuditLog.ACCOUNT_DELETED_BY_OPS);
    }

    private void erase(Account a, UUID by, String byRole, String reason, String action) {
        Instant now = clock.instant();
        String contact = a.loginContact();
        Optional<Organization.Membership> membership = organizations.membership(a.id());
        membership.ifPresent(m -> organizations.removeMember(m.orgId(), a.id()));
        for (PersonalDataHolder h : holders) {
            h.erase(a.id());
        }
        users.erase(a.id(), reason, by, now);
        int ended = sessions.revokeAll(a.id(), "ACCOUNT_DELETED");
        users.withdrawConsent(a.id(), "MARKETING", now);
        users.deleteRecoveryCodes(a.id());
        users.deleteOtpChallenges(a.id(), contact);
        users.deleteRateCounters(contact);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("accountType", a.type().name());
        details.put("sessionsEnded", ended);
        membership.ifPresent(m -> details.put("leftCompany", m.orgId().toString()));
        audit.record(by, byRole, action, a.id(), membership.map(Organization.Membership::orgId).orElse(null), reason,
                details);
    }

    private static void requireDeletable(Account a) {
        if (a.status() == AccountStatus.DELETED) {
            throw new AccountNotFoundException();
        }
        if (a.type() == AccountType.ADMIN) {
            throw new InvalidRequestException("Staff accounts are closed by suspension, not deleted");
        }
    }

    /**
     * @param locked inside the deletion transaction: the company row is locked, so a teammate cannot leave or be
     *               demoted at the same moment and leave a team without an admin
     */
    private void checkBlockers(Account a, boolean locked) {
        if (a.type() == AccountType.CREATOR) {
            List<UUID> open = creatorWork.activeJobIds(a.id());
            if (!open.isEmpty()) {
                throw new DeletionBlockedException("You have " + open.size() + " job(s) in progress (including "
                        + "payouts not yet closed). Finish them, or ask Loofie support to hand them over, then delete "
                        + "your account.", open);
            }
            return;
        }
        Optional<Organization.Membership> m = organizations.membership(a.id());
        if (m.isEmpty()) {
            return;
        }
        UUID org = m.get().orgId();
        if (locked) {
            organizations.findForUpdate(org);
        }
        List<Organization.Member> members = organizations.members(org);
        if (members.size() > 1 && "BRAND_ADMIN".equals(m.get().role()) && organizations.admins(org) <= 1) {
            throw new DeletionBlockedException("You are the only admin of your company's team. Make a teammate "
                    + "admin first, then delete your account.", List.of());
        }
        if (members.size() == 1) {
            List<UUID> open = companyWork.activeJobIds(org);
            if (!open.isEmpty()) {
                throw new DeletionBlockedException("Your company has " + open.size() + " job(s) in progress and you "
                        + "are its only member. Finish or cancel them first.", open);
            }
        }
    }
}
