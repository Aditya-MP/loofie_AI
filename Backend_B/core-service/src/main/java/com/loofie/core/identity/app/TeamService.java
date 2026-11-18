package com.loofie.core.identity.app;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.loofie.core.common.Ids;
import com.loofie.core.identity.domain.Account;
import com.loofie.core.identity.domain.AccountType;
import com.loofie.core.identity.domain.Channel;
import com.loofie.core.identity.domain.Contacts;
import com.loofie.core.identity.domain.IdentityExceptions.CompanyNotFoundException;
import com.loofie.core.identity.domain.IdentityExceptions.ContactTakenException;
import com.loofie.core.identity.domain.IdentityExceptions.InvalidRequestException;
import com.loofie.core.identity.domain.MessageSender;
import com.loofie.core.identity.domain.Organization;
import com.loofie.core.identity.domain.SessionView;
import com.loofie.core.identity.infra.InviteRepository;
import com.loofie.core.identity.infra.OrganizationRepository;
import com.loofie.core.identity.infra.UserRepository;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;

/**
 * A brand's team (flow 5, Q6, Q7): admins invite, change roles and remove; anyone may leave; there is always at least
 * one admin. Every change locks the company row first, so two admins acting at once cannot leave it without one.
 * Membership is read on every request, so a change works on the person's next request without ending their session.
 */
@Service
public class TeamService {

    private static final Logger log = LoggerFactory.getLogger(TeamService.class);

    public record Invite(UUID inviteId, String email, String role, Instant expiresAt, String link,
                         boolean emailSent) {
    }

    private final OrganizationRepository organizations;
    private final InviteRepository invites;
    private final UserRepository users;
    private final AuditLog audit;
    private final IdentityCrypto crypto;
    private final MessageSender sender;
    private final IdentityProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TeamService(OrganizationRepository organizations, InviteRepository invites, UserRepository users,
                       AuditLog audit, IdentityCrypto crypto, MessageSender sender, IdentityProperties props,
                       TransactionTemplate tx, Clock clock) {
        this.organizations = organizations;
        this.invites = invites;
        this.users = users;
        this.audit = audit;
        this.crypto = crypto;
        this.sender = sender;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    public Invite invite(Actor actor, String rawEmail, ActorRole role) {
        CompanyService.requireAdmin(actor);
        if (role != ActorRole.BRAND_ADMIN && role != ActorRole.BRAND_MEMBER) {
            throw new InvalidRequestException("role must be BRAND_ADMIN or BRAND_MEMBER");
        }
        String email = Contacts.email(rawEmail);
        String token = crypto.newToken();
        Invite created = tx.execute(status -> {
            Organization o = organizations.findForUpdate(actor.orgId()).orElseThrow(CompanyNotFoundException::new);
            users.findByContact(Channel.EMAIL, email).ifPresent(existing -> {
                if (existing.type() != AccountType.BRAND) {
                    throw new ContactTakenException("This email belongs to a Loofie account that is not a brand "
                            + "account. Use another email.");
                }
                organizations.membership(existing.id()).ifPresent(m -> {
                    throw new ContactTakenException(m.orgId().equals(o.id()) ? "This person is already in your team"
                            : "This person belongs to another company on Loofie. They must leave it first.");
                });
            });
            Instant now = clock.instant();
            invites.revokeOpen(o.id(), email, now); // a new invite replaces an older one to the same email
            UUID id = Ids.newId();
            Instant expires = now.plus(props.inviteTtl());
            invites.insert(id, email, role.name(), o.id(), IdentityCrypto.sha256(token), actor.userId(), now, expires);
            audit.record(actor, AuditLog.MEMBER_INVITED, null, o.id(), null, Map.of("inviteId", id, "email",
                    Contacts.mask(email), "role", role.name()));
            return new Invite(id, email, role.name(), expires, props.appBaseUrl() + "/team/invite#" + token, false);
        });
        boolean sent;
        try {
            sender.send(new MessageSender.Message(Channel.EMAIL, email, "TEAM_INVITE", "Join your team on Loofie",
                    "You have been invited to join your company's team on Loofie as "
                            + (role == ActorRole.BRAND_ADMIN ? "an admin" : "a member") + ". Open this link within "
                            + props.inviteTtl().toDays() + " days: " + created.link()));
            sent = true;
        } catch (RuntimeException e) {
            log.warn("Team invite email to {} not sent: {}", Contacts.mask(email), e.toString());
            sent = false;
        }
        return new Invite(created.inviteId(), email, created.role(), created.expiresAt(), created.link(), sent);
    }

    /** Someone who already has a brand account (and no company) opens the link while logged in. */
    @Transactional
    public Organization acceptAsExistingUser(SessionView s, String token) {
        Account a = users.findForUpdate(s.userId()).orElseThrow();
        if (a.type() != AccountType.BRAND) {
            throw new ActorNotAllowedException("Only brand accounts can join a company");
        }
        Instant now = clock.instant();
        InviteRepository.Invite invite = AuthService.usableInvite(invites, token, now);
        if (invite.orgId() == null) {
            throw new InvalidRequestException("This is a staff invite. Open it from the staff login.");
        }
        if (!invite.email().equals(a.email())) {
            throw new InvalidRequestException("This invite was sent to another email. Log in with that email.");
        }
        try {
            organizations.addMember(invite.orgId(), a.id(), invite.role(), invite.invitedBy(), now);
        } catch (DuplicateKeyException e) {
            throw new ContactTakenException("You already belong to a company on Loofie. Leave it first.");
        }
        invites.markAccepted(invite.id(), a.id(), now);
        users.markOnboarded(a.id(), now);
        audit.record(a.id(), invite.role(), AuditLog.MEMBER_JOINED, a.id(), invite.orgId(), null,
                Map.of("inviteId", invite.id(), "invitedBy", invite.invitedBy(), "role", invite.role()));
        return organizations.find(invite.orgId()).orElseThrow();
    }

    @Transactional
    public void revokeInvite(Actor actor, UUID inviteId) {
        CompanyService.requireAdmin(actor);
        if (!invites.revoke(inviteId, actor.orgId(), clock.instant())) {
            throw new InvalidRequestException("No open invite with that id in your company");
        }
        audit.record(actor, AuditLog.INVITE_REVOKED, null, actor.orgId(), null, Map.of("inviteId", inviteId));
    }

    @Transactional
    public void changeRole(Actor actor, UUID userId, ActorRole role) {
        CompanyService.requireAdmin(actor);
        if (role != ActorRole.BRAND_ADMIN && role != ActorRole.BRAND_MEMBER) {
            throw new InvalidRequestException("role must be BRAND_ADMIN or BRAND_MEMBER");
        }
        organizations.findForUpdate(actor.orgId()).orElseThrow(CompanyNotFoundException::new);
        String current = currentRole(actor.orgId(), userId);
        if (current.equals(role.name())) {
            return;
        }
        if (current.equals(ActorRole.BRAND_ADMIN.name())) {
            requireAnotherAdmin(actor.orgId(), "Make someone else an admin first: a company always needs one");
        }
        organizations.setRole(actor.orgId(), userId, role.name());
        audit.record(actor, AuditLog.MEMBER_ROLE_CHANGED, userId, actor.orgId(), null,
                Map.of("from", current, "to", role.name()));
    }

    /** An admin removes someone. Removing yourself is leaving. */
    @Transactional
    public void remove(Actor actor, UUID userId) {
        if (userId.equals(actor.userId())) {
            leave(actor);
            return;
        }
        CompanyService.requireAdmin(actor);
        organizations.findForUpdate(actor.orgId()).orElseThrow(CompanyNotFoundException::new);
        String role = currentRole(actor.orgId(), userId);
        if (role.equals(ActorRole.BRAND_ADMIN.name())) {
            requireAnotherAdmin(actor.orgId(), "A company always needs an admin");
        }
        organizations.removeMember(actor.orgId(), userId);
        users.markNotOnboarded(userId, clock.instant());
        audit.record(actor, AuditLog.MEMBER_REMOVED, userId, actor.orgId(), null, Map.of("role", role));
    }

    @Transactional
    public void leave(Actor actor) {
        CompanyService.requireBrand(actor);
        organizations.findForUpdate(actor.orgId()).orElseThrow(CompanyNotFoundException::new);
        String role = currentRole(actor.orgId(), actor.userId());
        if (role.equals(ActorRole.BRAND_ADMIN.name())) {
            requireAnotherAdmin(actor.orgId(), organizations.members(actor.orgId()).size() == 1
                    ? "You are the only person in this company. Contact support to close it."
                    : "Make someone else an admin before you leave");
        }
        organizations.removeMember(actor.orgId(), actor.userId());
        users.markNotOnboarded(actor.userId(), clock.instant());
        audit.record(actor, AuditLog.MEMBER_LEFT, actor.userId(), actor.orgId(), null, Map.of("role", role));
    }

    private String currentRole(UUID orgId, UUID userId) {
        return organizations.members(orgId).stream().filter(m -> m.userId().equals(userId)).findFirst()
                .map(Organization.Member::role)
                .orElseThrow(() -> new InvalidRequestException("This person is not in your company"));
    }

    /** Read under the company row lock. */
    private void requireAnotherAdmin(UUID orgId, String message) {
        if (organizations.admins(orgId) <= 1) {
            throw new InvalidRequestException(message);
        }
    }
}
