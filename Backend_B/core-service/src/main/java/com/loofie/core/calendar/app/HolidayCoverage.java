package com.loofie.core.calendar.app;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.loofie.core.calendar.domain.WorkingDays;
import com.loofie.core.calendar.infra.HolidayRepository;
import com.loofie.core.notification.app.NotificationService;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Makes "the holiday list ran out" visible before it hurts (G1). A year with fewer national holidays than expected
 * was never (fully) entered, and from then on brand clocks skip too few days. Read-only checks on every instance;
 * values are cached for the metrics scrape. When something is missing, ops gets a {@code HOLIDAYS_MISSING} notice in
 * its inbox, at most once a day across all instances (final pass K3), so it reaches a person before hosting alerts
 * exist.
 *
 * <p>{@code loofie.calendar.expected-national-per-year} (K2): 3 (the fixed days 26 Jan, 15 Aug, 2 Oct) until ops
 * delivers the real list; then set it to that list's national count, so a half-entered year counts as missing.
 *
 * <p>Alert on {@code loofie.calendar.next.year.missing = 1} (from 1 December) and
 * {@code loofie.calendar.national.holidays.next365} below the expected count.
 */
@Component
public class HolidayCoverage {

    /** Republic Day, Independence Day, Gandhi Jayanti: in every year's list whatever else is in it. */
    static final int MIN_NATIONAL_PER_YEAR = 3;
    /** From this date on, next year's list is expected to be entered. */
    static final Month NEXT_YEAR_DUE_FROM = Month.DECEMBER;
    public static final String NOTICE = "HOLIDAYS_MISSING";

    private static final Logger log = LoggerFactory.getLogger(HolidayCoverage.class);

    private final HolidayRepository holidays;
    private final NotificationService notifications;
    private final Clock clock;
    private final int expected;
    private final AtomicLong next365;
    private final AtomicLong nextYear;
    private final AtomicLong nextYearMissing = new AtomicLong();

    public HolidayCoverage(HolidayRepository holidays, NotificationService notifications, Clock clock,
                           MeterRegistry meters,
                           @Value("${loofie.calendar.expected-national-per-year:" + MIN_NATIONAL_PER_YEAR + "}")
                           int expected) {
        if (expected < MIN_NATIONAL_PER_YEAR || expected > 60) {
            throw new IllegalArgumentException("loofie.calendar.expected-national-per-year must be "
                    + MIN_NATIONAL_PER_YEAR + "-60");
        }
        this.holidays = holidays;
        this.notifications = notifications;
        this.clock = clock;
        this.expected = expected;
        this.next365 = new AtomicLong(expected);
        this.nextYear = new AtomicLong(expected);
        Gauge.builder("loofie.calendar.national.holidays.next365", next365, AtomicLong::get)
                .description("National holidays entered for the next 365 days (below the expected count = missing)")
                .register(meters);
        Gauge.builder("loofie.calendar.national.holidays.next.year", nextYear, AtomicLong::get)
                .description("National holidays entered for next calendar year").register(meters);
        Gauge.builder("loofie.calendar.next.year.missing", nextYearMissing, AtomicLong::get)
                .description("1 from 1 December when next year's holidays are not (fully) entered").register(meters);
        Gauge.builder("loofie.calendar.national.holidays.expected", () -> expected)
                .description("National holidays a full year should have (config)").register(meters);
    }

    @Scheduled(fixedDelayString = "${loofie.calendar.coverage-interval:6h}", initialDelayString = "1m")
    public void refresh() {
        try {
            Instant now = clock.instant();
            LocalDate today = now.atZone(WorkingDays.INDIA).toLocalDate();
            long ahead = holidays.countNational(today, today.plusDays(365));
            int year = today.getYear() + 1;
            long next = holidays.countNational(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
            boolean missing = today.getMonth() == NEXT_YEAR_DUE_FROM && next < expected;
            next365.set(ahead);
            nextYear.set(next);
            nextYearMissing.set(missing ? 1 : 0);
            if (missing) {
                log.warn("Holidays for {} are not fully entered ({} national found, {} expected): from 1 January "
                        + "brand deadlines will skip too few days. Ops: add the {} list.", year, next, expected, year);
                tellOps("NEXT_YEAR_MISSING", year, next, now);
            } else if (ahead < expected) {
                log.warn("Only {} national holidays entered for the next 365 days ({} expected): the list has run out",
                        ahead, expected);
                tellOps("NEXT_365_DAYS_SHORT", year, ahead, now);
            }
        } catch (RuntimeException e) {
            log.warn("Holiday coverage check failed: {}", e.toString());
        }
    }

    private void tellOps(String why, int year, long found, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("why", why);
        payload.put("year", year);
        payload.put("nationalFound", found);
        payload.put("expected", expected);
        notifications.toOpsOncePerDay(NOTICE, payload, now);
    }

    long next365() {
        return next365.get();
    }

    long nextYear() {
        return nextYear.get();
    }

    boolean nextYearMissing() {
        return nextYearMissing.get() == 1;
    }
}
