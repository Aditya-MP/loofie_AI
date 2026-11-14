package com.loofie.core.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.loofie.core.support.IntegrationTest;

/** Working days: Monday to Saturday, minus national holidays and the brand's state holidays. */
@IntegrationTest
class HolidayCalendarTest {

    @Autowired HolidayCalendar calendar;

    @Test
    void saturdayCountsAndSundayDoesNot() {
        // Thursday 12 Nov 2026, 15:00 IST. Fri 13 (1), Sat 14 (2), Sun 15 skipped, Mon 16 (3).
        Instant thursday = Instant.parse("2026-11-12T09:30:00Z");
        assertThat(calendar.endOfWorkingDays(thursday, 3, null)).isEqualTo(Instant.parse("2026-11-16T18:30:00Z"));
    }

    @Test
    void daysAreCountedInIndiaTime() {
        // 20:00 UTC Thursday is already Friday 01:30 in India, so counting starts from Friday.
        Instant lateThursdayUtc = Instant.parse("2026-11-12T20:00:00Z");
        // Sat 14 (1), Sun skipped, Mon 16 (2), Tue 17 (3) -> end of Tuesday IST.
        assertThat(calendar.endOfWorkingDays(lateThursdayUtc, 3, null)).isEqualTo(Instant.parse("2026-11-17T18:30:00Z"));
    }

    @Test
    void nationalHolidaySeededByTheMigrationIsSkipped() {
        // Thu 1 Oct 2026. Fri 2 Oct = Gandhi Jayanti (seeded), Sat 3 (1), Sun skipped, Mon 5 (2), Tue 6 (3).
        Instant thursday = Instant.parse("2026-10-01T05:00:00Z");
        assertThat(calendar.endOfWorkingDays(thursday, 3, null)).isEqualTo(Instant.parse("2026-10-06T18:30:00Z"));
    }

    @Test
    void stateHolidayAppliesOnlyToBrandsInThatState() {
        calendar.upsert(LocalDate.of(2026, 10, 19), "wb", "Durga Puja (test)", null);
        Instant friday = Instant.parse("2026-10-16T05:00:00Z");

        // National: Sat 17 (1), Mon 19 (2), Tue 20 (3).
        assertThat(calendar.endOfWorkingDays(friday, 3, null)).isEqualTo(Instant.parse("2026-10-20T18:30:00Z"));
        assertThat(calendar.endOfWorkingDays(friday, 3, "KA")).isEqualTo(Instant.parse("2026-10-20T18:30:00Z"));
        // Bengal: Mon 19 is a holiday -> Sat 17 (1), Tue 20 (2), Wed 21 (3).
        assertThat(calendar.endOfWorkingDays(friday, 3, "WB")).isEqualTo(Instant.parse("2026-10-21T18:30:00Z"));

        assertThat(calendar.list(2026)).anySatisfy(h -> {
            assertThat(h.stateCode()).isEqualTo("WB");
            assertThat(h.name()).isEqualTo("Durga Puja (test)");
        });
        assertThat(calendar.delete(LocalDate.of(2026, 10, 19), "WB")).isTrue();
    }
}
