package com.loofie.core.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.loofie.core.calendar.domain.Holiday;
import com.loofie.core.calendar.domain.StateCodes;
import com.loofie.core.calendar.domain.StateCodes.InvalidStateCodeException;
import com.loofie.core.calendar.domain.WorkingDays;

/** The working-day rule as a pure function: no database (docs/features/working-day-calendar.md, G6). */
class WorkingDaysTest {

    // Thursday 12 Nov 2026, 15:00 IST
    static final Instant THURSDAY = Instant.parse("2026-11-12T09:30:00Z");

    static Instant endOfDayIst(String date) {
        return LocalDate.parse(date).plusDays(1).atStartOfDay(WorkingDays.INDIA).toInstant();
    }

    @Test
    void consecutiveHolidaysAreAllSkipped() {
        // Diwali-style run: Fri 13 and Sat 14 closed, Sun 15 never counts -> Mon 16 (1), Tue 17 (2), Wed 18 (3).
        Set<LocalDate> closed = Set.of(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 14));
        assertThat(WorkingDays.endOf(THURSDAY, 3, closed)).isEqualTo(endOfDayIst("2026-11-18"));
    }

    @Test
    void aHolidayOnASundayIsNotSkippedTwice() {
        // Fri 13 (1), Sat 14 (2), Sun 15 (holiday and Sunday: skipped once), Mon 16 (3).
        Set<LocalDate> closed = Set.of(LocalDate.of(2026, 11, 15));
        assertThat(WorkingDays.endOf(THURSDAY, 3, closed)).isEqualTo(WorkingDays.endOf(THURSDAY, 3, Set.of()))
                .isEqualTo(endOfDayIst("2026-11-16"));
    }

    @Test
    void countingCrossesTheYearEnd() {
        // Wed 30 Dec 2026: Thu 31 (1), Fri 1 Jan closed, Sat 2 (2), Sun skipped, Mon 4 Jan 2027 (3).
        Instant wednesday = Instant.parse("2026-12-30T06:00:00Z");
        assertThat(WorkingDays.endOf(wednesday, 3, Set.of(LocalDate.of(2027, 1, 1))))
                .isEqualTo(endOfDayIst("2027-01-04"));
    }

    @Test
    void aClockStartedOnAHolidayStartsCountingTheNextDay() {
        // Accepted on Fri 13 (a holiday): Sat 14 (1), Mon 16 (2).
        Instant onHoliday = Instant.parse("2026-11-13T04:30:00Z");
        assertThat(WorkingDays.endOf(onHoliday, 2, Set.of(LocalDate.of(2026, 11, 13))))
                .isEqualTo(endOfDayIst("2026-11-16"));
    }

    @Test
    void aCalendarWithNoWorkingDaysInTheLookaheadFailsLoudlyInsteadOfLooping() {
        Set<LocalDate> everyDay = new HashSet<>();
        LocalDate d = LocalDate.of(2026, 11, 12);
        for (int i = 0; i <= WorkingDays.LOOKAHEAD_DAYS + 1; i++) {
            everyDay.add(d.plusDays(i));
        }
        assertThatThrownBy(() -> WorkingDays.endOf(THURSDAY, 1, everyDay)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WorkingDays.endOf(THURSDAY, 0, Set.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stateCodesAreTheOfficialListWithRetiredCodesMappedAndTyposRefused() {
        assertThat(StateCodes.CODES).hasSize(36);
        assertThat(StateCodes.normalise(" wb ")).isEqualTo("WB");
        assertThat(StateCodes.normalise("IN-ka")).isEqualTo("KA");
        assertThat(StateCodes.normalise("OR")).isEqualTo("OD");
        assertThat(StateCodes.normalise("CT")).isEqualTo("CG");
        assertThat(StateCodes.normalise("TG")).isEqualTo("TS");
        assertThat(StateCodes.normalise("UT")).isEqualTo("UK");
        assertThat(StateCodes.normalise(null)).isEqualTo(Holiday.NATIONAL);
        assertThat(StateCodes.normalise("  ")).isEqualTo(Holiday.NATIONAL);
        for (String typo : new String[] {"WBB", "XX", "Bengal", "IN-", "W"}) {
            assertThatThrownBy(() -> StateCodes.normalise(typo)).isInstanceOf(InvalidStateCodeException.class);
        }
    }

    /** The database CHECK and the Java list must never drift apart. */
    @Test
    void theDatabaseCheckListsExactlyTheSameCodes() throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V9__holiday_audit_and_state_codes.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String check = sql.substring(sql.indexOf("holidays_state_code_check"));
        Set<String> inSql = new HashSet<>();
        Matcher m = Pattern.compile("'([A-Z]{2})'").matcher(check);
        while (m.find()) {
            inSql.add(m.group(1));
        }
        assertThat(inSql).isEqualTo(StateCodes.CODES);
    }
}
