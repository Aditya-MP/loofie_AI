package com.loofie.core.calendar.app;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Month;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.loofie.core.calendar.domain.WorkingDays;
import com.loofie.core.calendar.infra.HolidayRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Makes "the holiday list ran out" visible before it hurts (G1). Every year has at least the 3 fixed national
 * holidays (26 Jan, 15 Aug, 2 Oct); fewer means that year was never entered, and from then on brand clocks skip
 * no holiday at all. Read-only, so it runs on every instance; values are cached for the metrics scrape.
 *
 * <p>Alert on {@code loofie.calendar.next.year.missing = 1} (from 1 December) and
 * {@code loofie.calendar.national.holidays.next365 < 3}.
 */
@Component
public class HolidayCoverage {

    /** Republic Day, Independence Day, Gandhi Jayanti: in every year's list whatever else is in it. */
    static final int MIN_NATIONAL_PER_YEAR = 3;
    /** From this date on, next year's list is expected to be entered. */
    static final Month NEXT_YEAR_DUE_FROM = Month.DECEMBER;

    private static final Logger log = LoggerFactory.getLogger(HolidayCoverage.class);

    private final HolidayRepository holidays;
    private final Clock clock;
    private final AtomicLong next365 = new AtomicLong(MIN_NATIONAL_PER_YEAR);
    private final AtomicLong nextYear = new AtomicLong(MIN_NATIONAL_PER_YEAR);
    private final AtomicLong nextYearMissing = new AtomicLong();

    public HolidayCoverage(HolidayRepository holidays, Clock clock, MeterRegistry meters) {
        this.holidays = holidays;
        this.clock = clock;
        Gauge.builder("loofie.calendar.national.holidays.next365", next365, AtomicLong::get)
                .description("National holidays entered for the next 365 days (fewer than 3 = a year is missing)")
                .register(meters);
        Gauge.builder("loofie.calendar.national.holidays.next.year", nextYear, AtomicLong::get)
                .description("National holidays entered for next calendar year").register(meters);
        Gauge.builder("loofie.calendar.next.year.missing", nextYearMissing, AtomicLong::get)
                .description("1 from 1 December when next year's holidays are not entered").register(meters);
    }

    @Scheduled(fixedDelayString = "${loofie.calendar.coverage-interval:6h}", initialDelayString = "1m")
    public void refresh() {
        try {
            LocalDate today = clock.instant().atZone(WorkingDays.INDIA).toLocalDate();
            long ahead = holidays.countNational(today, today.plusDays(365));
            int year = today.getYear() + 1;
            long next = holidays.countNational(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
            boolean missing = today.getMonth() == NEXT_YEAR_DUE_FROM && next < MIN_NATIONAL_PER_YEAR;
            next365.set(ahead);
            nextYear.set(next);
            nextYearMissing.set(missing ? 1 : 0);
            if (missing) {
                log.warn("Holidays for {} are not entered ({} national found): from 1 January brand deadlines will "
                        + "skip no holidays. Ops: add the {} list.", year, next, year);
            } else if (ahead < MIN_NATIONAL_PER_YEAR) {
                log.warn("Only {} national holidays entered for the next 365 days: the list has run out", ahead);
            }
        } catch (RuntimeException e) {
            log.debug("Holiday coverage refresh failed: {}", e.toString());
        }
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
