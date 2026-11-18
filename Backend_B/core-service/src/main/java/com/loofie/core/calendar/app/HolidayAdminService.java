package com.loofie.core.calendar.app;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.calendar.domain.Holiday;
import com.loofie.core.calendar.domain.WorkingDays;
import com.loofie.core.calendar.infra.HolidayRepository;
import com.loofie.core.calendar.domain.HolidayChange;
import com.loofie.core.calendar.domain.HolidayChange.Outcome;
import com.loofie.core.job.app.RunningClocks;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.JobExceptions.InvalidQueryException;

/**
 * F10: ops edits the holiday table. Only OPS may read or change it; the check lives here, not in the controller, so
 * every entry point gets it. Every change is recorded in {@code holiday_changes}.
 *
 * <p><b>Running clocks are not recalculated</b> (decision R3 in F6, confirmed for the calendar): moving a deadline
 * someone was already told about is worse. The answer to an edit says how many running clocks it does not reach.
 */
@Service
public class HolidayAdminService {

    /**
     * State holidays only apply once a brand's pickup state is known (brand profiles, F1). Until then every clock
     * counts national holidays only ({@code JobDeadlines} passes no state). Flip this when F1 passes the state.
     */
    static final boolean STATE_HOLIDAYS_IN_USE = true; // F1 round B: each job carries its brand's pickup state

    /** What an edit did, and what it did not reach. */
    public record EditResult(Outcome outcome, String stateCode, long runningClocksNotChanged,
                             boolean stateHolidaysInUse, String note) {
    }

    private final HolidayRepository holidays;
    private final RunningClocks clocks;
    private final Clock clock;

    public HolidayAdminService(HolidayRepository holidays, RunningClocks clocks, Clock clock) {
        this.holidays = holidays;
        this.clocks = clocks;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Holiday> list(Actor actor, int year) {
        requireOps(actor);
        requireYear(year);
        return holidays.between(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    @Transactional(readOnly = true)
    public List<HolidayChange> changes(Actor actor, int year) {
        requireOps(actor);
        requireYear(year);
        return holidays.changes(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    @Transactional
    public EditResult upsert(Actor actor, LocalDate date, String stateCode, String name) {
        requireOps(actor);
        String state = Holiday.normaliseState(stateCode);
        Instant now = clock.instant();
        Outcome outcome = holidays.upsert(date, state, name.trim(), actor.userId(), now);
        return result(outcome, date, state, now);
    }

    @Transactional
    public boolean delete(Actor actor, LocalDate date, String stateCode) {
        requireOps(actor);
        return holidays.delete(date, Holiday.normaliseState(stateCode), actor.userId(), clock.instant());
    }

    private EditResult result(Outcome outcome, LocalDate date, String state, Instant now) {
        Instant dayStart = date.atStartOfDay(WorkingDays.INDIA).toInstant();
        // Only a future (or today's) holiday can fall inside a clock that is running now.
        long notChanged = outcome == Outcome.UNCHANGED || dayStart.isBefore(now.minus(java.time.Duration.ofDays(1)))
                ? 0 : clocks.workingDayClocksEndingAfter(dayStart);
        boolean stateHoliday = !state.equals(Holiday.NATIONAL);
        String note;
        if (stateHoliday && !STATE_HOLIDAYS_IN_USE) {
            note = "Saved, but state holidays do not affect any deadline yet: brand pickup states are not known "
                    + "until brand profiles exist. Only national holidays count today.";
        } else if (notChanged > 0) {
            note = notChanged + " running shipping or dispute deadlines end after this date and were set before it; "
                    + "they are not moved. New deadlines skip it" + (stateHoliday
                    ? " for brands whose pickup state is " + state + "." : ".");
        } else {
            note = stateHoliday ? "New deadlines skip this date for brands whose pickup state is " + state + "."
                    : "New deadlines skip this date.";
        }
        return new EditResult(outcome, state, notChanged, STATE_HOLIDAYS_IN_USE, note);
    }

    private static void requireYear(int year) {
        if (year < 2000 || year > 2100) {
            throw new InvalidQueryException("year must be between 2000 and 2100");
        }
    }

    private static void requireOps(Actor actor) {
        actor.requireRole(ActorRole.OPS, "Holidays are managed by OPS only");
    }
}
