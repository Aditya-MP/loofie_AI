package com.loofie.core.notification.app;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.common.Ids;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobExceptions.InvalidQueryException;
import com.loofie.core.notification.infra.NotificationRepository;
import com.loofie.core.notification.infra.NotificationRepository.Inbox;

import tools.jackson.databind.json.JsonMapper;

/**
 * The in-app inbox: what each brand, creator and ops person is told (reminders, alerts, refund decisions).
 * Written in the same transaction as the fact it reports, so a notice is never lost or sent for something that
 * rolled back. External delivery (WhatsApp, email) can later read the same rows. Ids and codes only.
 */
@Service
public class NotificationService {

    public static final String BRAND_ORG = "BRAND_ORG";
    public static final String CREATOR = "CREATOR";
    public static final String OPS = "OPS";
    public static final String FOUNDER = "FOUNDER";
    static final int MAX_PAGE = 100;

    public record Item(UUID id, String kind, UUID jobId, Map<String, Object> payload, Instant createdAt,
                       boolean read) {
    }

    private final NotificationRepository repo;
    private final JsonMapper json;
    private final Clock clock;

    public NotificationService(NotificationRepository repo, JsonMapper json, Clock clock) {
        this.repo = repo;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void toBrand(UUID brandOrgId, String kind, UUID jobId, Map<String, Object> payload, Instant at) {
        repo.insert(Ids.newId(), new Inbox(BRAND_ORG, brandOrgId), kind, jobId, json.writeValueAsString(payload), at);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void toCreator(UUID creatorId, String kind, UUID jobId, Map<String, Object> payload, Instant at) {
        repo.insert(Ids.newId(), new Inbox(CREATOR, creatorId), kind, jobId, json.writeValueAsString(payload), at);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void toOps(String kind, UUID jobId, Map<String, Object> payload, Instant at) {
        repo.insert(Ids.newId(), new Inbox(OPS, null), kind, jobId, json.writeValueAsString(payload), at);
    }

    /**
     * A standing alarm for ops (e.g. the holiday list ran out): at most one per kind per India day, however many
     * instances check. Its own transaction. Returns false when today's was already sent.
     */
    @Transactional
    public boolean toOpsOncePerDay(String kind, Map<String, Object> payload, Instant at) {
        Instant dayStart = at.atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate()
                .atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
        return repo.insertOncePerDay(Ids.newId(), new Inbox(OPS, null), kind, json.writeValueAsString(payload), at,
                dayStart);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void toFounder(String kind, UUID jobId, Map<String, Object> payload, Instant at) {
        repo.insert(Ids.newId(), new Inbox(FOUNDER, null), kind, jobId, json.writeValueAsString(payload), at);
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Item> inbox(Actor actor, boolean unreadOnly, Instant before, int limit) {
        return inbox(actor, unreadOnly, before, null, limit);
    }

    /** @param jobId only what was sent about this job (null = everything in the caller's inboxes) */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Item> inbox(Actor actor, boolean unreadOnly, Instant before, UUID jobId, int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw new InvalidQueryException("limit must be between 1 and " + MAX_PAGE);
        }
        return repo.list(inboxesOf(actor), unreadOnly, before, jobId, limit).stream()
                .map(r -> new Item(r.id(), r.kind(), r.jobId(), json.readValue(r.payload(), Map.class), r.createdAt(),
                        r.readAt() != null))
                .toList();
    }

    @Transactional
    public boolean markRead(Actor actor, UUID id) {
        return repo.markRead(id, inboxesOf(actor), clock.instant());
    }

    /** Each person sees only their own inboxes: their brand's, their own creator inbox, the ops one. */
    static List<Inbox> inboxesOf(Actor actor) {
        return switch (actor.role()) {
            case BRAND_ADMIN, BRAND_MEMBER -> List.of(new Inbox(BRAND_ORG, actor.orgId()));
            case CREATOR -> List.of(new Inbox(CREATOR, actor.userId()));
            case OPS, OPS_LEAD -> List.of(new Inbox(OPS, null));
            case FOUNDER -> List.of(new Inbox(OPS, null), new Inbox(FOUNDER, null));
            case SYSTEM -> throw new IllegalArgumentException("SYSTEM has no inbox");
        };
    }
}
