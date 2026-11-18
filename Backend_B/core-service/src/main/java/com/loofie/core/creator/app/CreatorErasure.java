package com.loofie.core.creator.app;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.common.Ids;
import com.loofie.core.creator.infra.CreatorProfileRepository;
import com.loofie.core.creator.infra.EvidenceRepository;
import com.loofie.core.identity.app.PersonalDataHolder;
import com.loofie.core.messaging.BackendACreatorCalls;
import com.loofie.core.messaging.EventEnvelope;
import com.loofie.core.messaging.Outbox;

/**
 * Account deletion, creator side: the passport's personal fields and numbers go, screenshots are taken down (the
 * files themselves by the upload cleanup), and Backend A is asked to delete everything it fetched from Instagram
 * ({@code DELETE /ai/instagram/accounts/{ref}?delete_data=true}, through the outbox: retried until done, so a Backend
 * A outage never blocks a deletion; a 4xx answer is parked for ops like any rejected row). Sent for every creator,
 * connected or not: Backend A answers 404 when it has
 * nothing, which counts as done. Known gap: the manual profile sent with POST /ai/creators/profile is not deleted,
 * because Backend A has no endpoint for it yet (asked).
 */
@Component
class CreatorErasure implements PersonalDataHolder {

    private final CreatorProfileRepository profiles;
    private final EvidenceRepository evidence;
    private final Outbox outbox;
    private final Clock clock;

    CreatorErasure(CreatorProfileRepository profiles, EvidenceRepository evidence, Outbox outbox, Clock clock) {
        this.profiles = profiles;
        this.evidence = evidence;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void erase(UUID userId) {
        if (profiles.find(userId).isEmpty()) {
            return; // a brand, or a creator who never opened the form
        }
        Instant now = clock.instant();
        profiles.erase(userId, now);
        evidence.removeAll(userId, now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("creatorRef", userId.toString());
        outbox.enqueue(Outbox.CREATOR_STREAM, userId.toString(), new EventEnvelope(Ids.newId(),
                BackendACreatorCalls.INSTAGRAM_DATA_DELETE, 1, now, EventEnvelope.PRODUCER, userId, 0, Ids.newId(),
                null, payload));
    }
}
