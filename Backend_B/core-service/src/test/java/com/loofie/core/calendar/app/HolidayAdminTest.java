package com.loofie.core.calendar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.loofie.core.calendar.app.HolidayAdminService.EditResult;
import com.loofie.core.calendar.domain.HolidayChange;
import com.loofie.core.calendar.domain.HolidayChange.Action;
import com.loofie.core.calendar.domain.HolidayChange.Outcome;
import com.loofie.core.calendar.domain.StateCodes.InvalidStateCodeException;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.job.domain.JobExceptions.InvalidQueryException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.JobFixtures.Seeded;
import com.loofie.core.support.MutableClock;

/** Holiday edits: history, validation, what an edit reaches, coverage alarm (docs/features/working-day-calendar.md). */
@IntegrationTest
class HolidayAdminTest {

    @Autowired HolidayAdminService admin;
    @Autowired HolidayCoverage coverage;
    @Autowired JobFixtures fixtures;
    @Autowired JdbcClient jdbc;
    @Autowired MutableClock clock;

    final Actor ops = Actor.ops(UUID.randomUUID());

    @Test
    void everyChangeIsRecordedWithWhoAndWhenAndARepeatIsNot() {
        LocalDate day = LocalDate.of(2028, 3, 10);
        assertThat(admin.upsert(ops, day, "MH", "Holi").outcome()).isEqualTo(Outcome.CREATED);
        assertThat(admin.upsert(ops, day, "mh", "Holi").outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(admin.upsert(ops, day, "MH", "Holi (Dhulandi)").outcome()).isEqualTo(Outcome.RENAMED);
        assertThat(admin.delete(ops, day, "MH")).isTrue();
        assertThat(admin.delete(ops, day, "MH")).isFalse();

        List<HolidayChange> history = admin.changes(ops, 2028).stream()
                .filter(c -> c.date().equals(day) && c.stateCode().equals("MH")).toList();
        assertThat(history).extracting(HolidayChange::action)
                .containsExactly(Action.INSERT, Action.RENAME, Action.DELETE);
        assertThat(history.get(1).oldName()).isEqualTo("Holi");
        assertThat(history.get(1).newName()).isEqualTo("Holi (Dhulandi)");
        assertThat(history.get(2).oldName()).isEqualTo("Holi (Dhulandi)");
        assertThat(history).allSatisfy(c -> {
            assertThat(c.changedBy()).isEqualTo(ops.userId());
            assertThat(c.changedAt()).isEqualTo(clock.instant());
        });
    }

    @Test
    void theHistoryCannotBeChangedOrDeleted() {
        admin.upsert(ops, LocalDate.of(2028, 3, 11), "", "Test day");
        assertThatThrownBy(() -> jdbc.sql("UPDATE holiday_changes SET new_name = 'x'").update())
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM holiday_changes").update())
                .rootCause().hasMessageContaining("append-only");
        admin.delete(ops, LocalDate.of(2028, 3, 11), "");
    }

    @Test
    void theSeededHolidaysStartTheHistory() {
        assertThat(admin.changes(ops, 2027)).filteredOn(c -> c.changedBy() == null)
                .extracting(HolidayChange::newName)
                .contains("Republic Day", "Independence Day", "Gandhi Jayanti");
    }

    @Test
    void anUnknownStateCodeIsRefusedAndARetiredOneIsStoredAsTheCurrentCode() {
        assertThatThrownBy(() -> admin.upsert(ops, LocalDate.of(2028, 4, 1), "WBB", "typo"))
                .isInstanceOf(InvalidStateCodeException.class);
        assertThat(admin.upsert(ops, LocalDate.of(2028, 4, 1), "TG", "Telangana day").stateCode()).isEqualTo("TS");
        // the database refuses it too, whatever path a row comes by
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO holidays (holiday_date, state_code, name) VALUES ('2028-04-02', 'XX', 'x')")
                .update()).hasMessageContaining("holidays_state_code_check");
        admin.delete(ops, LocalDate.of(2028, 4, 1), "TS");
    }

    @Test
    void onlyOpsMayReadOrChangeHolidays() {
        for (Actor other : List.of(Actor.creator(UUID.randomUUID()), Actor.system())) {
            assertThatThrownBy(() -> admin.list(other, 2027)).isInstanceOf(ActorNotAllowedException.class);
            assertThatThrownBy(() -> admin.changes(other, 2027)).isInstanceOf(ActorNotAllowedException.class);
            assertThatThrownBy(() -> admin.upsert(other, LocalDate.of(2028, 5, 1), "", "x"))
                    .isInstanceOf(ActorNotAllowedException.class);
            assertThatThrownBy(() -> admin.delete(other, LocalDate.of(2027, 1, 26), ""))
                    .isInstanceOf(ActorNotAllowedException.class);
        }
        assertThatThrownBy(() -> admin.list(ops, 10_000_000)).isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void anEditSaysWhichRunningDeadlinesItDoesNotMove() {
        // A brand's shipping clock running past Friday 20 Nov 2026 (the test clock is Monday 16 Nov).
        Seeded job = fixtures.jobIn(JobStatus.CREATOR_ACCEPTED);
        jdbc.sql("UPDATE jobs SET ship_due_at = ? WHERE id = ?")
                .params(Instant.parse("2026-11-21T18:30:00Z").atOffset(ZoneOffset.UTC), job.jobId()).update();

        EditResult national = admin.upsert(ops, LocalDate.of(2026, 11, 20), "", "Test closure");
        assertThat(national.runningClocksNotChanged()).isPositive();
        assertThat(national.note()).contains("not moved");

        EditResult past = admin.upsert(ops, LocalDate.of(2026, 1, 2), "", "Long ago");
        assertThat(past.runningClocksNotChanged()).as("a past day is inside no running clock").isZero();

        EditResult state = admin.upsert(ops, LocalDate.of(2026, 11, 20), "WB", "Test state closure");
        assertThat(state.stateHolidaysInUse()).isFalse();
        assertThat(state.note()).contains("do not affect any deadline yet");

        admin.delete(ops, LocalDate.of(2026, 11, 20), "");
        admin.delete(ops, LocalDate.of(2026, 11, 20), "WB");
        admin.delete(ops, LocalDate.of(2026, 1, 2), "");
        jdbc.sql("UPDATE jobs SET ship_due_at = NULL WHERE id = ?").param(job.jobId()).update();
    }

    @Test
    void theAlarmRingsWhenNextYearsHolidaysAreMissing() {
        Instant before = clock.instant();
        try {
            clock.set(Instant.parse("2026-12-05T06:00:00Z")); // 2027 is seeded
            coverage.refresh();
            assertThat(coverage.nextYear()).isGreaterThanOrEqualTo(HolidayCoverage.MIN_NATIONAL_PER_YEAR);
            assertThat(coverage.nextYearMissing()).isFalse();

            clock.set(Instant.parse("2027-11-20T06:00:00Z")); // November: 2028 not due yet
            coverage.refresh();
            assertThat(coverage.nextYearMissing()).isFalse();

            clock.set(Instant.parse("2027-12-05T06:00:00Z")); // December: 2028 is due and not entered
            coverage.refresh();
            assertThat(coverage.nextYearMissing()).isTrue();
            assertThat(coverage.next365()).as("the list has run out").isLessThan(HolidayCoverage.MIN_NATIONAL_PER_YEAR);
        } finally {
            clock.set(before);
            coverage.refresh();
        }
    }
}
