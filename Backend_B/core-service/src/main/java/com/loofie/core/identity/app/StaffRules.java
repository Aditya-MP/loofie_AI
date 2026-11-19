package com.loofie.core.identity.app;

import com.loofie.core.identity.domain.Account;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;

/**
 * Who may do what to whom (agreed 2026-11-17, Q13): FOUNDER invites any staff role, OPS_LEAD invites OPS. Any ops
 * person acts on creator and brand accounts; only a FOUNDER acts on staff accounts (OPS_LEAD may reset an OPS
 * person's authenticator). Nobody acts on their own account through these tools.
 */
final class StaffRules {

    private StaffRules() {
    }

    static void requireOps(Actor actor) {
        if (!actor.role().isOps()) {
            throw new ActorNotAllowedException("Only Loofie staff can do this");
        }
    }

    static void requireSenior(Actor actor) {
        if (actor.role() != ActorRole.FOUNDER && actor.role() != ActorRole.OPS_LEAD) {
            throw new ActorNotAllowedException("Only a founder or ops lead can do this");
        }
    }

    static void requireMayInvite(Actor actor, ActorRole role) {
        requireOps(actor);
        boolean ok = actor.role() == ActorRole.FOUNDER || (actor.role() == ActorRole.OPS_LEAD && role == ActorRole.OPS);
        if (!ok) {
            throw new ActorNotAllowedException(actor.role() == ActorRole.OPS_LEAD
                    ? "An ops lead can invite ops staff only" : "Only a founder or ops lead can invite staff");
        }
    }

    /** Suspend, unsuspend, change contact. */
    static void requireMayManage(Actor actor, Account target) {
        requireOps(actor);
        if (target.id().equals(actor.userId())) {
            throw new ActorNotAllowedException("You cannot do this to your own account");
        }
        if (target.isStaff() && actor.role() != ActorRole.FOUNDER) {
            throw new ActorNotAllowedException("Only a founder can change staff accounts");
        }
    }

    static void requireMayResetAuthenticator(Actor actor, Account target) {
        requireOps(actor);
        if (!target.isStaff()) {
            throw new ActorNotAllowedException("Only staff accounts have an authenticator");
        }
        if (target.id().equals(actor.userId())) {
            throw new ActorNotAllowedException(target.staffRole() == ActorRole.OPS
                    ? "Another founder or ops lead must reset your authenticator"
                    : "Another founder must reset your authenticator");
        }
        boolean ok = actor.role() == ActorRole.FOUNDER
                || (actor.role() == ActorRole.OPS_LEAD && target.staffRole() == ActorRole.OPS);
        if (!ok) {
            throw new ActorNotAllowedException("Only a founder can reset this person's authenticator");
        }
    }

    static String requireReason(String reason) {
        String r = reason == null ? "" : reason.trim();
        if (r.length() < 3 || r.length() > 500) {
            throw new com.loofie.core.identity.domain.IdentityExceptions.InvalidRequestException(
                    "Give a reason (3-500 characters); it is recorded and may be shown in an appeal");
        }
        return r;
    }
}
