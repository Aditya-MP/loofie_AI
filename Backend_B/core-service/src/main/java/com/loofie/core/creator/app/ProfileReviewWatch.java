package com.loofie.core.creator.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.common.LeaderLease;
import com.loofie.core.creator.infra.CreatorProfileRepository;
import com.loofie.core.notification.app.NotificationService;

/**
 * Keeps the 24-48 h review promise visible: a profile waiting 24 h tells ops, 48 h tells the founder. Each profile is
 * reported once per wait (a resubmission starts a new wait); several profiles crossing together make one notice.
 */
@Component
public class ProfileReviewWatch {

    private static final Logger log = LoggerFactory.getLogger(ProfileReviewWatch.class);
    public static final String NOTICE_OVERDUE = "PROFILE_REVIEW_OVERDUE";
    public static final String NOTICE_ESCALATED = "PROFILE_REVIEW_ESCALATED";
    static final Duration OPS_AFTER = Duration.ofHours(24);
    static final Duration FOUNDER_AFTER = Duration.ofHours(48);
    static final int MAX_IDS_IN_NOTICE = 20;

    public record Result(int toOps, int toFounder) {
    }

    private final CreatorProfileRepository profiles;
    private final NotificationService notifications;
    private final Clock clock;

    public ProfileReviewWatch(CreatorProfileRepository profiles, NotificationService notifications, Clock clock) {
        this.profiles = profiles;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    public Result runOnce() {
        Instant now = clock.instant();
        List<UUID> overdue = profiles.claimOverdue(false, now.minus(OPS_AFTER), now);
        if (!overdue.isEmpty()) {
            notifications.toOps(NOTICE_OVERDUE, null, payload(overdue, 24), now);
        }
        List<UUID> escalated = profiles.claimOverdue(true, now.minus(FOUNDER_AFTER), now);
        if (!escalated.isEmpty()) {
            notifications.toFounder(NOTICE_ESCALATED, null, payload(escalated, 48), now);
            log.warn("{} creator profile(s) waiting over 48 h for review; founder told", escalated.size());
        }
        return new Result(overdue.size(), escalated.size());
    }

    private static Map<String, Object> payload(List<UUID> ids, int hours) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("count", ids.size());
        p.put("waitingOverHours", hours);
        p.put("creatorIds", ids.stream().limit(MAX_IDS_IN_NOTICE).map(UUID::toString).toList());
        p.put("action", "Open the profile review queue: /api/core/ops/creators/review-queue");
        return p;
    }

    /** Runs on the instance holding the "profile-review-watch" lease. */
    @Component
    @ConditionalOnProperty(name = "loofie.creators.review-watch-enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduler {

        private final ProfileReviewWatch watch;
        private final LeaderLease lease;

        Scheduler(ProfileReviewWatch watch, LeaderLease lease) {
            this.watch = watch;
            this.lease = lease;
        }

        @Scheduled(fixedDelayString = "${loofie.creators.review-watch-interval:30m}", initialDelayString = "3m")
        void run() {
            try {
                if (lease.tryAcquire("profile-review-watch", Duration.ofMinutes(5))) {
                    watch.runOnce();
                }
            } catch (RuntimeException e) {
                log.error("Profile review watch failed", e);
            }
        }
    }
}
