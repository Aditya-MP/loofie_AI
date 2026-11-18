package com.loofie.core.creator.app;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.loofie.core.common.DependencyUnavailableException;
import com.loofie.core.common.Ids;
import com.loofie.core.creator.domain.CreatorExceptions.CreatorNotFoundException;
import com.loofie.core.creator.domain.CreatorForm;
import com.loofie.core.creator.domain.CreatorProfile;
import com.loofie.core.creator.domain.CreatorProfile.MarketplaceStatus;
import com.loofie.core.creator.domain.Evidence;
import com.loofie.core.creator.domain.InstagramGateway;
import com.loofie.core.creator.domain.MetricValue;
import com.loofie.core.creator.domain.MetricValue.Metric;
import com.loofie.core.creator.domain.MetricValue.Source;
import com.loofie.core.creator.infra.CreatorProfileRepository;
import com.loofie.core.creator.infra.EvidenceRepository;
import com.loofie.core.identity.app.AccountDirectory;
import com.loofie.core.identity.app.AuditLog;
import com.loofie.core.identity.domain.IdentityExceptions.InvalidRequestException;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.messaging.BackendACreatorCalls;
import com.loofie.core.messaging.EventEnvelope;
import com.loofie.core.messaging.Outbox;
import com.loofie.core.storage.Uploads;

/**
 * The creator's own passport (flows 1 and 2): see it, fill it in (progress saved), and complete it. Completion is
 * decided here, in one place, for every path: the form, an Instagram connection, a screenshot.
 *
 * <p>Complete = every required field AND numbers she can stand behind: Instagram connected and verified by Backend A
 * right now, or self-reported followers + average views with at least one Insights screenshot (decision C4: listed
 * at once, labelled SELF_REPORTED; ops checks the screenshot in its queue).
 */
@Service
@EnableConfigurationProperties(CreatorProperties.class)
public class CreatorProfileService {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    public record NumberView(Metric metric, long value, Source source, LocalDate capturedOn) {
    }

    public record EvidenceView(UUID id, Evidence.Kind kind, Evidence.Status status, String reviewNote,
                               Instant createdAt) {
    }

    public record InstagramView(String handle, CreatorProfile.InstagramStatus status, Boolean insightsAllowed,
                                Instant checkedAt) {
    }

    public record Options(List<String> categories, List<String> languages, List<CreatorProfile.Format> formats,
                          List<String> uploadTypes, long maxUploadBytes) {
    }

    /**
     * Everything her form and dashboard need.
     *
     * @param missing         what is still needed before the passport is complete, in form order (empty when complete)
     * @param verifiedUnavailable Backend A could not be asked for her verified numbers just now (shown as "updating")
     */
    public record ProfileView(UUID creatorId, String displayName, String city, String state, List<String> languages,
                              List<String> categories, List<CreatorProfile.Format> formats, Integer ratePerReelRupees,
                              Boolean partnershipAds, Integer turnaroundDays, String bio, List<String> sampleLinks,
                              InstagramView instagram, List<NumberView> numbers, List<EvidenceView> evidence,
                              CreatorProfile.Level level, MarketplaceStatus marketplaceStatus,
                              String marketplaceReason, boolean completed, Instant completedAt, List<String> missing,
                              boolean bookable, boolean verifiedUnavailable, Options options) {
    }

    private final CreatorProfileRepository profiles;
    private final EvidenceRepository evidence;
    private final InstagramGateway instagram;
    private final AccountDirectory accounts;
    private final AuditLog audit;
    private final Outbox outbox;
    private final CreatorProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CreatorProfileService(CreatorProfileRepository profiles, EvidenceRepository evidence,
                                 InstagramGateway instagram, AccountDirectory accounts, AuditLog audit, Outbox outbox,
                                 CreatorProperties props, TransactionTemplate tx, Clock clock) {
        this.profiles = profiles;
        this.evidence = evidence;
        this.instagram = instagram;
        this.accounts = accounts;
        this.audit = audit;
        this.outbox = outbox;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    /** Her passport; the empty row is created on first sight, so the form always has something to load. */
    public ProfileView mine(Actor actor) {
        UUID me = requireCreator(actor);
        profiles.insertIfAbsent(me, clock.instant());
        return view(me, true);
    }

    /**
     * Saves what she typed (partial is fine before completion) and completes the passport when nothing is missing.
     * After completion a required field can be changed but not emptied.
     */
    public ProfileView save(Actor actor, CreatorForm raw) {
        UUID me = requireCreator(actor);
        if (raw == null) {
            throw new InvalidRequestException("The profile form is required");
        }
        CreatorForm form = CreatorForm.clean(raw, Set.copyOf(props.categories()), Set.copyOf(props.languages()));
        profiles.insertIfAbsent(me, clock.instant());
        tx.executeWithoutResult(status -> {
            CreatorProfile current = profiles.findForUpdate(me).orElseThrow();
            Instant now = clock.instant();
            if (current.completed() && !form.missingFields().isEmpty()) {
                throw new InvalidRequestException("These are required once your profile is complete: "
                        + String.join(", ", form.missingFields()));
            }
            profiles.saveForm(current.withForm(form, now));
            LocalDate today = LocalDate.ofInstant(now, INDIA);
            if (form.followers() != null) {
                profiles.upsertMetric(me, new MetricValue(Metric.FOLLOWERS, form.followers(), Source.SELF_REPORTED,
                        today), me, now);
            }
            if (form.avgViews() != null) {
                profiles.upsertMetric(me, new MetricValue(Metric.AVG_VIEWS_10, form.avgViews(), Source.SELF_REPORTED,
                        today), me, now);
            }
            settle(me, actor, true);
        });
        return view(me, true);
    }

    /** She pauses (HIDDEN) or resumes (LISTED) her own listing. A block by ops is not hers to lift. */
    @Transactional
    public ProfileView setListed(Actor actor, boolean listed) {
        UUID me = requireCreator(actor);
        CreatorProfile p = profiles.findForUpdate(me).orElseThrow(() -> new CreatorNotFoundException(
                "Fill in your profile first"));
        if (p.marketplaceStatus() == MarketplaceStatus.BLOCKED) {
            throw new InvalidRequestException("Your profile was paused by Loofie: " + p.marketplaceReason()
                    + ". Contact support.");
        }
        profiles.setMarketplace(me, listed ? MarketplaceStatus.LISTED : MarketplaceStatus.HIDDEN, null,
                clock.instant());
        return view(me, false);
    }

    /**
     * Re-checks completion after anything that can complete the passport (form, Instagram, screenshot). Backend A gets
     * the profile when it has just been completed, or when {@code changed} says what it holds changed (form, level,
     * checked numbers); never on a status poll that changed nothing (the app polls every 2 s). Runs inside the caller's
     * transaction, with her row already locked.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settle(UUID creatorId, Actor actor, boolean changed) {
        CreatorProfile p = profiles.find(creatorId).orElseThrow();
        Instant now = clock.instant();
        boolean newlyCompleted = false;
        if (!p.completed() && missing(p).isEmpty()) {
            profiles.markCompleted(creatorId, now);
            accounts.setOnboarded(creatorId, true);
            audit.record(actor, AuditLog.PROFILE_COMPLETED, creatorId, null, Map.of("path",
                    p.instagramVerified() ? "INSTAGRAM" : "MANUAL"));
            p = profiles.find(creatorId).orElseThrow();
            newlyCompleted = true;
        }
        if (p.completed() && (newlyCompleted || changed)) {
            pushToBackendA(p, now);
        }
    }

    /** What is still needed, in form order. Empty = complete. */
    public List<String> missing(CreatorProfile p) {
        CreatorForm asForm = new CreatorForm(p.displayName(), p.city(), p.state(), p.languages(), p.categories(),
                p.formats(), p.ratePerReelRupees(), p.partnershipAds(), p.turnaroundDays(), p.bio(), p.sampleLinks(),
                p.instagramHandle(), null, null);
        List<String> m = new ArrayList<>(asForm.missingFields());
        if (!p.instagramVerified()) {
            List<MetricValue> numbers = profiles.metrics(p.creatorId());
            if (numbers.stream().noneMatch(n -> n.metric() == Metric.FOLLOWERS)) {
                m.add("followers");
            }
            if (numbers.stream().noneMatch(n -> n.metric() == Metric.AVG_VIEWS_10)) {
                m.add("avgViews");
            }
            if (evidence.live(p.creatorId(), Evidence.Kind.INSIGHTS) == 0) {
                m.add("insightsScreenshot");
            }
        }
        return m;
    }

    /** The full view; {@code withVerified} also asks Backend A for her verified numbers (only when connected). */
    public ProfileView view(UUID creatorId, boolean withVerified) {
        CreatorProfile p = profiles.find(creatorId).orElseThrow(() -> new CreatorNotFoundException("No such creator"));
        List<NumberView> numbers = new ArrayList<>(profiles.metrics(creatorId).stream()
                .map(n -> new NumberView(n.metric(), n.value(), n.source(), n.capturedOn())).toList());
        boolean verifiedUnavailable = false;
        if (withVerified && p.instagramVerified() && p.instagramHandle() != null) {
            try {
                instagram.verifiedNumbers(p.instagramHandle()).ifPresent(v -> {
                    if (v.followers() != null) {
                        numbers.add(new NumberView(Metric.FOLLOWERS, v.followers(), Source.API_VERIFIED,
                                v.followersCapturedOn()));
                    }
                    if (v.medianViews() != null) {
                        numbers.add(new NumberView(Metric.AVG_VIEWS_10, v.medianViews(), Source.API_VERIFIED,
                                v.viewsCapturedOn()));
                    }
                });
            } catch (DependencyUnavailableException e) {
                verifiedUnavailable = true;
            }
        }
        // Most trusted first: API_VERIFIED, OPS_CHECKED, SELF_REPORTED.
        numbers.sort(Comparator.comparing(NumberView::metric).thenComparing(n -> switch (n.source()) {
            case API_VERIFIED -> 0;
            case OPS_CHECKED -> 1;
            case SELF_REPORTED -> 2;
        }));
        List<EvidenceView> ev = evidence.forCreator(creatorId).stream()
                .filter(e -> e.status() != Evidence.Status.REMOVED)
                .map(e -> new EvidenceView(e.id(), e.kind(), e.status(), e.reviewNote(), e.createdAt())).toList();
        List<String> missing = p.completed() ? List.of() : missing(p);
        boolean bookable = p.completed() && p.marketplaceStatus() == MarketplaceStatus.LISTED
                && accounts.active(creatorId);
        return new ProfileView(creatorId, p.displayName(), p.city(), p.state(), p.languages(), p.categories(),
                p.formats(), p.ratePerReelRupees(), p.partnershipAds(), p.turnaroundDays(), p.bio(), p.sampleLinks(),
                new InstagramView(p.instagramHandle(), p.instagramStatus(), p.instagramInsightsAllowed(),
                        p.instagramCheckedAt()), numbers, ev, p.level(), p.marketplaceStatus(),
                p.marketplaceReason(), p.completed(), p.completedAt(), missing, bookable, verifiedUnavailable,
                options());
    }

    public Options options() {
        return new Options(props.categories(), props.languages(), List.of(CreatorProfile.Format.values()),
                Uploads.Purpose.CREATOR_EVIDENCE.types(), Uploads.Purpose.CREATOR_EVIDENCE.maxBytes());
    }

    /** Queues her profile for Backend A's quality check (self-reported numbers, descriptions, level). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void pushToBackendA(CreatorProfile p, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("creatorRef", p.creatorId().toString());
        payload.put("handle", p.instagramHandle());
        payload.put("displayName", p.displayName());
        payload.put("city", p.city());
        payload.put("bio", p.bio());
        payload.put("languages", p.languages());
        payload.put("level", p.level().name());
        best(profiles.metrics(p.creatorId()), Metric.FOLLOWERS).ifPresent(v -> payload.put("followers", v.value()));
        outbox.enqueue(Outbox.CREATOR_STREAM, p.creatorId().toString(), new EventEnvelope(Ids.newId(),
                BackendACreatorCalls.PROFILE_UPDATED, 1, now, EventEnvelope.PRODUCER, p.creatorId(), p.version(),
                Ids.newId(), null, payload));
    }

    /** OPS_CHECKED wins over SELF_REPORTED. */
    static Optional<MetricValue> best(List<MetricValue> values, Metric metric) {
        return values.stream().filter(v -> v.metric() == metric)
                .min(Comparator.comparing(v -> v.source() == Source.OPS_CHECKED ? 0 : 1));
    }

    static UUID requireCreator(Actor actor) {
        if (actor.role() != ActorRole.CREATOR) {
            throw new com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException(
                    "Only creators have a creator profile");
        }
        return actor.userId();
    }
}
