package com.loofie.core.creator.app;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.loofie.core.common.ScreenReadFence;
import com.loofie.core.creator.domain.CreatorProfile.Level;
import com.loofie.core.creator.infra.CreatorProfileRepository;
import com.loofie.core.creator.infra.CreatorProfileRepository.SearchFilter;
import com.loofie.core.creator.infra.CreatorProfileRepository.SearchRow;
import com.loofie.core.creator.infra.EvidenceRepository;
import com.loofie.core.identity.domain.IdentityExceptions.InvalidRequestException;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;

/**
 * F10: ops finds creators to offer (F9) or check: a plain directory with filters, cheapest first. Ranking and
 * shortlists stay Backend A's (F3). No phone or email in the results.
 */
@Service
public class OpsCreatorSearch {

    static final int MAX_PAGE = 100;

    public record Result(UUID creatorId, String displayName, String instagramHandle, String city, String state,
                         List<String> categories, List<String> languages, Integer ratePerReelRupees, String level,
                         String reviewStatus, String marketplaceStatus, boolean bookable) {
    }

    public record Page(List<Result> items, String next) {
    }

    public record Queues(long profilesToReview, long screenshotsToCheck) {
    }

    private final CreatorProfileRepository profiles;
    private final EvidenceRepository evidence;
    private final ScreenReadFence fence;

    public OpsCreatorSearch(CreatorProfileRepository profiles, EvidenceRepository evidence, ScreenReadFence fence) {
        this.profiles = profiles;
        this.evidence = evidence;
        this.fence = fence;
    }

    public Page search(Actor actor, String category, String city, String state, String language, Integer maxRate,
                       String level, String q, Boolean bookable, String after, int size) {
        actor.requireRole(ActorRole.OPS, "Ops only");
        int n = Math.max(1, Math.min(size <= 0 ? 25 : size, MAX_PAGE));
        Level lvl = null;
        if (level != null && !level.isBlank()) {
            try {
                lvl = Level.valueOf(level.strip().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestException("level must be NEW or VERIFIED");
            }
        }
        String query = blank(q);
        if (query != null && query.length() < 2) {
            throw new InvalidRequestException("Type at least 2 characters to search by name or handle");
        }
        Integer afterRate = null;
        UUID afterId = null;
        if (after != null && !after.isBlank()) {
            String[] p = after.split("_", 2);
            try {
                afterRate = Integer.valueOf(p[0]);
                afterId = UUID.fromString(p[1]);
            } catch (RuntimeException e) {
                throw new InvalidRequestException("after must be the next value of the previous page");
            }
        }
        SearchFilter f = new SearchFilter(blank(category), blank(city), blank(state) == null ? null
                : state.strip().toUpperCase(), blank(language), maxRate, lvl, query == null ? null
                : query.replaceFirst("^@", ""), bookable == null || bookable);
        Integer ar = afterRate;
        UUID ai = afterId;
        return fence.read("creators", () -> {
            List<SearchRow> rows = profiles.search(f, ar, ai, n);
            List<Result> items = rows.stream().map(r -> new Result(r.creatorId(), r.displayName(), r.instagramHandle(),
                    r.city(), r.state(), r.categories(), r.languages(), r.ratePerReelRupees(), r.level().name(),
                    r.reviewStatus(), r.marketplaceStatus().name(), r.bookable())).toList();
            String next = items.size() < n ? null
                    : items.getLast().ratePerReelRupees() + "_" + items.getLast().creatorId();
            return new Page(items, next);
        });
    }

    /** For the ops overview. */
    public Queues queues() {
        return new Queues(profiles.pendingReviews(), evidence.pendingCount());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
