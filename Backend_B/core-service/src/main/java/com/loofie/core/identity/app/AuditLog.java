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
        record(actor.userId(), actor.role().name(), action, target, reason, details);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorId, String actorRole, String action, UUID target, String reason,
                       Map<String, Object> details) {
        repo.insert(new com.loofie.core.identity.domain.AuditEntry(Ids.newId(), clock.instant(), actorId, actorRole, action, target, reason,
                details == null || details.isEmpty() ? null : json.writeValueAsString(details),
                RequestIdFilter.current()));
    }

    public List<com.loofie.core.identity.domain.AuditEntry> list(UUID target, Instant before, int limit) {
        return repo.list(target, before, limit);
    }
}
