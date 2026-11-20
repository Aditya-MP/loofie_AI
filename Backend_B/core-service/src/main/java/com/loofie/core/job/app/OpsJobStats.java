package com.loofie.core.job.app;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.job.domain.JobDeadlines;
import com.loofie.core.job.domain.WorkingCalendar;
import com.loofie.core.job.infra.OpsStatsRepository;
import com.loofie.core.job.infra.OpsStatsRepository.Span;

/** F10: the job side of the ops overview (counts, overdue clocks, last-30-days dispute and replacement speed). */
@Service
public class OpsJobStats {

    public record Jobs(Map<String, Long> byStatus, Map<String, Long> overdue, long waitingForCreator,
                       long openSwapOffers) {
    }

    /**
     * @param disputesDecided           disputes decided in the period
     * @param disputesOnTimePercent     of those, decided within the 2-working-day promise (null when none)
     * @param replacementsFound         swaps approved in the period
     * @param medianDaysToReplacement   from losing the creator to inviting the new one (null when none)
     */
    public record Performance(int disputesDecided, Double disputesOnTimePercent, int replacementsFound,
                              Double medianDaysToReplacement) {
    }

    private final OpsStatsRepository stats;
    private final WorkingCalendar calendar;

    public OpsJobStats(OpsStatsRepository stats, WorkingCalendar calendar) {
        this.stats = stats;
        this.calendar = calendar;
    }

    @Transactional(readOnly = true)
    public Jobs jobs(Instant now) {
        Map<String, Long> byStatus = stats.jobsByStatus();
        long waiting = byStatus.getOrDefault("DECLINED", 0L) + byStatus.getOrDefault("REPLACEMENT_NEEDED", 0L);
        return new Jobs(byStatus, stats.overdue(now), waiting, stats.openSwapOffers());
    }

    @Transactional(readOnly = true)
    public Performance performance(Instant since) {
        List<Span> disputes = stats.disputesDecided(since).stream().filter(s -> s.entered() != null).toList();
        long onTime = disputes.stream().filter(s -> !s.left().isAfter(calendar.endOfWorkingDays(s.entered(),
                JobDeadlines.DISPUTE_REVIEW_WORKING_DAYS, null))).count();
        List<Double> days = stats.replacementsFound(since).stream().filter(s -> s.entered() != null)
                .map(s -> Duration.between(s.entered(), s.left()).toMinutes() / (60.0 * 24)).sorted().toList();
        return new Performance(disputes.size(), disputes.isEmpty() ? null : round(100.0 * onTime / disputes.size()),
                days.size(), days.isEmpty() ? null : round(median(days)));
    }

    private static double median(List<Double> sorted) {
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
