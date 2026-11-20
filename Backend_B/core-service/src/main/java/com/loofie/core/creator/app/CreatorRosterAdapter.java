package com.loofie.core.creator.app;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.creator.domain.CreatorProfile;
import com.loofie.core.creator.domain.CreatorProfile.Level;
import com.loofie.core.creator.infra.CreatorProfileRepository;
import com.loofie.core.identity.app.AuditLog;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.CreatorRoster;
import com.loofie.core.notification.app.NotificationService;

/** F9: the creator side of {@link CreatorRoster} (replacement candidates, level drop after a missed deadline). */
@Component
class CreatorRosterAdapter implements CreatorRoster {

    static final String LEVEL_DROPPED = "LEVEL_DROPPED_MISSED_DEADLINE";

    private final CreatorProfileRepository profiles;
    private final CreatorDirectory directory;
    private final CreatorProfileService profileService;
    private final AuditLog audit;
    private final NotificationService notifications;

    CreatorRosterAdapter(CreatorProfileRepository profiles, CreatorDirectory directory,
                         CreatorProfileService profileService, AuditLog audit, NotificationService notifications) {
        this.profiles = profiles;
        this.directory = directory;
        this.profileService = profileService;
        this.audit = audit;
        this.notifications = notifications;
    }

    @Override
    public Optional<Card> bookableCard(UUID creatorId) {
        if (!directory.bookable(creatorId)) {
            return Optional.empty();
        }
        return profiles.find(creatorId).map(CreatorRosterAdapter::card);
    }

    private static Card card(CreatorProfile p) {
        return new Card(p.creatorId(), p.displayName(), p.instagramHandle(), p.city(), p.level().name(),
                p.categories(), p.ratePerReelRupees(), p.partnershipAds(), p.sampleLinks());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void missedProductionDeadline(UUID creatorId, UUID jobId, Instant now) {
        CreatorProfile p = profiles.findForUpdate(creatorId).orElse(null);
        if (p == null || p.level() == Level.NEW) {
            return; // no profile (dev data) or already NEW: nothing to drop
        }
        String why = "Missed the production deadline on job " + jobId;
        profiles.setLevel(creatorId, Level.NEW, why, now);
        audit.record(Actor.system(), AuditLog.LEVEL_CHANGED, creatorId, why,
                Map.of("from", p.level().name(), "to", Level.NEW.name(), "jobId", jobId.toString()));
        notifications.toCreator(creatorId, LEVEL_DROPPED, jobId, Map.of("level", Level.NEW.name()), now);
        profileService.settle(creatorId, Actor.system(), true); // Backend A gets the new level
    }
}
