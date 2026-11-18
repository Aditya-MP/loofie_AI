package com.loofie.core.identity.app;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.common.Ids;
import com.loofie.core.common.web.RequestIdFilter;
import com.loofie.core.identity.infra.AuditRepository;
import com.loofie.core.job.domain.Actor;

import tools.jackson.databind.json.JsonMapper;

/**
 * Every staff action on a person, plus contact changes: who, what, to whom, why, from which request. Written in the
 * same transaction as the action, so there is never an action without its record. Append-only; kept 1 year.
 */
@Component
public class AuditLog {

    public static final String SUSPENDED = "ACCOUNT_SUSPENDED";
    public static final String UNSUSPENDED = "ACCOUNT_UNSUSPENDED";
    public static final String VIEWED = "ACCOUNT_VIEWED";
    public static final String CONTACT_CHANGED = "CONTACT_CHANGED";
    public static final String CONTACT_CHANGED_BY_OPS = "CONTACT_CHANGED_BY_OPS";
    public static final String STAFF_INVITED = "STAFF_INVITED";
    public static final String STAFF_JOINED = "STAFF_JOINED";
    public static final String TOTP_ENROLLED = "TOTP_ENROLLED";
    public static final String TOTP_RESET = "TOTP_RESET";
    public static final String RECOVERY_CODE_USED = "RECOVERY_CODE_USED";
    public static final String FOUNDER_BOOTSTRAPPED = "FOUNDER_BOOTSTRAPPED";
    // Round B: companies and teams
    public static final String ORG_CREATED = "ORG_CREATED";
    public static final String ORG_UPDATED = "ORG_UPDATED";
    public static final String ORG_VIEWED = "ORG_VIEWED";
    public static final String GSTIN_SET = "GSTIN_SET";
    public static final String GSTIN_SET_BY_OPS = "GSTIN_SET_BY_OPS";
    public static final String MEMBER_INVITED = "MEMBER_INVITED";
    public static final String INVITE_REVOKED = "INVITE_REVOKED";
    public static final String MEMBER_JOINED = "MEMBER_JOINED";
    public static final String MEMBER_ROLE_CHANGED = "MEMBER_ROLE_CHANGED";
    public static final String MEMBER_REMOVED = "MEMBER_REMOVED";
    public static final String MEMBER_LEFT = "MEMBER_LEFT";
    // Round C: creator passport
    public static final String PROFILE_COMPLETED = "CREATOR_PROFILE_COMPLETED";
    public static final String INSTAGRAM_CONNECTED = "INSTAGRAM_CONNECTED";
    public static final String INSTAGRAM_DISCONNECTED = "INSTAGRAM_DISCONNECTED";
    public static final String CREATOR_VIEWED = "CREATOR_VIEWED";
    public static final String EVIDENCE_VIEWED = "EVIDENCE_VIEWED";
    public static final String EVIDENCE_ACCEPTED = "EVIDENCE_ACCEPTED";
    public static final String EVIDENCE_REJECTED = "EVIDENCE_REJECTED";
    public static final String METRIC_CHECKED = "METRIC_OPS_CHECKED";
    public static final String LEVEL_CHANGED = "CREATOR_LEVEL_CHANGED";
    public static final String MARKETPLACE_BLOCKED = "CREATOR_BLOCKED";
    public static final String MARKETPLACE_UNBLOCKED = "CREATOR_UNBLOCKED";
    public static final String PROFILE_APPROVED = "CREATOR_PROFILE_APPROVED";
    public static final String PROFILE_CHANGES_REQUESTED = "CREATOR_PROFILE_CHANGES_REQUESTED";
    public static final String PROFILE_RESUBMITTED = "CREATOR_PROFILE_RESUBMITTED";
    public static final String PROFILE_REVIEW_REOPENED = "CREATOR_PROFILE_REVIEW_REOPENED";
    // Round C part 2: account deletion
    public static final String ACCOUNT_DELETED = "ACCOUNT_DELETED";
    public static final String ACCOUNT_DELETED_BY_OPS = "ACCOUNT_DELETED_BY_OPS";

    private final AuditRepository repo;
    private final JsonMapper json;
    private final Clock clock;

    public AuditLog(AuditRepository repo, JsonMapper json, Clock clock) {
        this.repo = repo;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Actor actor, String action, UUID target, String reason, Map<String, Object> details) {
        record(actor.userId(), actor.role().name(), action, target, null, reason, details);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Actor actor, String action, UUID targetUser, UUID targetOrg, String reason,
                       Map<String, Object> details) {
        record(actor.userId(), actor.role().name(), action, targetUser, targetOrg, reason, details);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorId, String actorRole, String action, UUID target, String reason,
                       Map<String, Object> details) {
        record(actorId, actorRole, action, target, null, reason, details);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorId, String actorRole, String action, UUID targetUser, UUID targetOrg, String reason,
                       Map<String, Object> details) {
        repo.insert(new com.loofie.core.identity.domain.AuditEntry(Ids.newId(), clock.instant(), actorId, actorRole,
                action, targetUser, targetOrg, reason,
                details == null || details.isEmpty() ? null : json.writeValueAsString(details),
                RequestIdFilter.current()));
    }

    public List<com.loofie.core.identity.domain.AuditEntry> list(UUID target, Instant before, int limit) {
        return repo.list(target, null, before, limit);
    }

    public List<com.loofie.core.identity.domain.AuditEntry> list(UUID targetUser, UUID targetOrg, Instant before,
                                                                  int limit) {
        return repo.list(targetUser, targetOrg, before, limit);
    }
}
