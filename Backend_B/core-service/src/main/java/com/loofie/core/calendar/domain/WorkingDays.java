package com.loofie.core.calendar.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

/**
 * Working-day rules for brand-side clocks, as pure functions: Monday to Saturday (Indian couriers
 * pick up on Saturdays), minus the given holidays, counted in India time. No database, no Spring.
 */
public final class WorkingDays {

    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** How far ahead holidays are looked up; far more than any brand-side clock needs. */
    public static final int LOOKAHEAD_DAYS = 120;

    private WorkingDays() {
    }

    /** The India-time date {@code from} falls on: counting starts the day after it. */
    public static LocalDate startDate(Instant from) {
        return from.atZone(INDIA).toLocalDate();
    }

    /**
     * The end of the {@code days}-th working day after {@code from}: midnight at the start of the
     * next day, India time.
     *
     * @param closed holidays between {@link #startDate(Instant)} and {@link #LOOKAHEAD_DAYS} later
     */
    public static Instant endOf(Instant from, int days, Set<LocalDate> closed) {
        if (days < 1) {
            throw new IllegalArgumentException("days must be >= 1");
        }
        LocalDate start = startDate(from);
        LocalDate limit = start.plusDays(LOOKAHEAD_DAYS);
        LocalDate day = start;
        int counted = 0;
        while (counted < days) {
            day = day.plusDays(1);
            if (day.isAfter(limit)) {
                throw new IllegalStateException("No " + days + " working days found within " + LOOKAHEAD_DAYS + " days");
            }
            if (isWorkingDay(day, closed)) {
                counted++;
            }
        }
        return day.plusDays(1).atStartOfDay(INDIA).toInstant();
    }

    public static boolean isWorkingDay(LocalDate day, Set<LocalDate> closed) {
        return day.getDayOfWeek() != DayOfWeek.SUNDAY && !closed.contains(day);
    }
}
