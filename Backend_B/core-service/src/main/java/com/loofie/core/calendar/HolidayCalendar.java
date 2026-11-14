package com.loofie.core.calendar;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.loofie.core.job.domain.WorkingCalendar;

/**
 * Working days for brand-side clocks: Monday to Saturday (Indian couriers pick up on Saturdays),
 * skipping holidays from the {@code holidays} table. A row with an empty state code is national;
 * otherwise it applies only to brands picking up in that state. Days are counted in India time.
 */
@Component
public class HolidayCalendar implements WorkingCalendar {

    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    public static final String NATIONAL = "";

    /** How far ahead holidays are loaded; far more than any brand-side clock needs. */
    private static final int LOOKAHEAD_DAYS = 120;

    public record Holiday(LocalDate date, String stateCode, String name) {
    }

    private final JdbcClient jdbc;

    public HolidayCalendar(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Instant endOfWorkingDays(Instant from, int days, String stateCode) {
        if (days < 1) {
            throw new IllegalArgumentException("days must be >= 1");
        }
        LocalDate start = from.atZone(INDIA).toLocalDate();
        Set<LocalDate> closed = closedDates(start, start.plusDays(LOOKAHEAD_DAYS), stateCode);

        LocalDate day = start;
        int counted = 0;
        while (counted < days) {
            day = day.plusDays(1);
            if (day.isAfter(start.plusDays(LOOKAHEAD_DAYS))) {
                throw new IllegalStateException("No " + days + " working days found within " + LOOKAHEAD_DAYS + " days");
            }
            if (day.getDayOfWeek() != DayOfWeek.SUNDAY && !closed.contains(day)) {
                counted++;
            }
        }
        // End of that working day = midnight at the start of the next day, India time.
        return day.plusDays(1).atStartOfDay(INDIA).toInstant();
    }

    private Set<LocalDate> closedDates(LocalDate from, LocalDate to, String stateCode) {
        String state = normalise(stateCode);
        List<LocalDate> dates = jdbc.sql("""
                SELECT holiday_date FROM holidays
                WHERE holiday_date BETWEEN :from AND :to AND state_code IN ('', :state)
                """)
                .param("from", from)
                .param("to", to)
                .param("state", state)
                .query(LocalDate.class)
                .list();
        return new HashSet<>(dates);
    }

    // ---- Ops editing (F10) ----

    public List<Holiday> list(int year) {
        return jdbc.sql("""
                SELECT holiday_date, state_code, name FROM holidays
                WHERE holiday_date BETWEEN :from AND :to ORDER BY holiday_date, state_code
                """)
                .param("from", LocalDate.of(year, 1, 1))
                .param("to", LocalDate.of(year, 12, 31))
                .query((rs, n) -> new Holiday(rs.getObject("holiday_date", LocalDate.class), rs.getString("state_code"),
                        rs.getString("name")))
                .list();
    }

    /** Adds or renames a holiday. Returns true when it was new. */
    public boolean upsert(LocalDate date, String stateCode, String name, UUID by) {
        int inserted = jdbc.sql("""
                INSERT INTO holidays (holiday_date, state_code, name, created_by) VALUES (:date, :state, :name, :by)
                ON CONFLICT (holiday_date, state_code) DO NOTHING
                """)
                .param("date", date).param("state", normalise(stateCode)).param("name", name.trim()).param("by", by)
                .update();
        if (inserted == 0) {
            jdbc.sql("UPDATE holidays SET name = :name WHERE holiday_date = :date AND state_code = :state")
                    .param("date", date).param("state", normalise(stateCode)).param("name", name.trim())
                    .update();
        }
        return inserted == 1;
    }

    public boolean delete(LocalDate date, String stateCode) {
        return jdbc.sql("DELETE FROM holidays WHERE holiday_date = :date AND state_code = :state")
                .param("date", date).param("state", normalise(stateCode))
                .update() == 1;
    }

    /** State codes are stored upper-case; null or blank means national. */
    static String normalise(String stateCode) {
        return stateCode == null || stateCode.isBlank() ? NATIONAL : stateCode.trim().toUpperCase();
    }
}
