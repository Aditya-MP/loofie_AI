package com.loofie.core.calendar.app;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.calendar.domain.Holiday;
import com.loofie.core.calendar.domain.StateCodes.InvalidStateCodeException;
import com.loofie.core.calendar.domain.WorkingDays;
import com.loofie.core.calendar.infra.HolidayRepository;
import com.loofie.core.job.app.RunningClocks;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.JobExceptions.InvalidQueryException;

/**
 * Loads one year's holiday list in one go (calendar final pass, K1): the list ops sends once a year replaces that
 * year. A preview (dry run, the default) answers what would change and writes nothing; applying it writes everything
 * or nothing, each change in {@code holiday_changes}. One bad row and nothing is applied.
 *
 * <p>Running clocks are not recalculated (decision D1); each change says how many running clocks it does not reach.
 */
@Service
public class HolidayYearImport {

    /** Far more than a year has (15-20 national, a few dozen with state holidays): a guard against a wrong file. */
    public static final int MAX_ROWS = 500;
    static final int MAX_NAME = 100;

    public record Row(LocalDate date, String stateCode, String name) {
    }

    public enum Change { ADDED, RENAMED, REMOVED, UNCHANGED }

    /** @param runningClocksNotChanged running shipping / dispute deadlines that end after this day and are not moved */
    public record Item(Change change, LocalDate date, String stateCode, String oldName, String newName,
                       long runningClocksNotChanged) {
    }

    public record Result(int year, boolean applied, int added, int renamed, int removed, int unchanged,
                         List<Item> changes, boolean stateHolidaysInUse, String note) {
    }

    /** @param row 1-based position in the list sent */
    public record RowError(int row, String error) {
    }

    /** One or more rows are wrong: nothing was applied. */
    public static class RejectedRows extends RuntimeException {
        private final transient List<RowError> rows;

        RejectedRows(List<RowError> rows) {
            super(rows.size() + " row(s) are wrong; nothing was applied");
            this.rows = List.copyOf(rows);
        }

        public List<RowError> rows() {
            return rows;
        }
    }

    private final HolidayRepository holidays;
    private final RunningClocks clocks;
    private final java.time.Clock clock;

    public HolidayYearImport(HolidayRepository holidays, RunningClocks clocks, java.time.Clock clock) {
        this.holidays = holidays;
        this.clocks = clocks;
        this.clock = clock;
    }

    @Transactional
    public Result importYear(Actor actor, int year, List<Row> rows, boolean dryRun) {
        actor.requireRole(ActorRole.OPS, "Holidays are managed by OPS only");
        if (year < 2000 || year > 2100) {
            throw new InvalidQueryException("year must be between 2000 and 2100");
        }
        Map<String, Row> wanted = validate(year, rows == null ? List.of() : rows);
        if (!dryRun) {
            holidays.lockYear(year); // two imports of the same year never interleave
        }
        Map<String, Holiday> current = new LinkedHashMap<>();
        for (Holiday h : holidays.between(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))) {
            current.put(key(h.date(), h.stateCode()), h);
        }

        Instant now = clock.instant();
        List<Item> items = new ArrayList<>();
        for (Map.Entry<String, Row> e : wanted.entrySet()) {
            Row r = e.getValue();
            Holiday was = current.get(e.getKey());
            Change change = was == null ? Change.ADDED : was.name().equals(r.name()) ? Change.UNCHANGED : Change.RENAMED;
            if (!dryRun && change != Change.UNCHANGED) {
                holidays.upsert(r.date(), r.stateCode(), r.name(), actor.userId(), now);
            }
            items.add(new Item(change, r.date(), r.stateCode(), was == null ? null : was.name(),
                    r.name(), change == Change.UNCHANGED ? 0 : reached(r.date(), now)));
        }
        for (Map.Entry<String, Holiday> e : current.entrySet()) {
            if (!wanted.containsKey(e.getKey())) {
                Holiday h = e.getValue();
                if (!dryRun) {
                    holidays.delete(h.date(), h.stateCode(), actor.userId(), now);
                }
                items.add(new Item(Change.REMOVED, h.date(), h.stateCode(), h.name(), null, reached(h.date(), now)));
            }
        }
        items.sort(java.util.Comparator.comparing(Item::date).thenComparing(Item::stateCode));

        int added = count(items, Change.ADDED);
        int renamed = count(items, Change.RENAMED);
        int removed = count(items, Change.REMOVED);
        int unchanged = count(items, Change.UNCHANGED);
        long reaching = items.stream().mapToLong(Item::runningClocksNotChanged).max().orElse(0);
        String note = (dryRun ? "Preview only, nothing saved. Send dryRun=false to apply. " : "Applied. ")
                + added + " added, " + renamed + " renamed, " + removed + " removed, " + unchanged + " unchanged."
                + (reaching > 0 ? " Running deadlines are not moved; new deadlines use the new list." : "")
                + (items.stream().anyMatch(i -> !i.stateCode().equals(Holiday.NATIONAL))
                        && !HolidayAdminService.STATE_HOLIDAYS_IN_USE
                        ? " State holidays are saved but do not affect deadlines until brand pickup states exist."
                        : items.stream().anyMatch(i -> !i.stateCode().equals(Holiday.NATIONAL))
                        ? " State holidays count for brands whose pickup state matches." : "");
        return new Result(year, !dryRun, added, renamed, removed, unchanged, items,
                HolidayAdminService.STATE_HOLIDAYS_IN_USE, note);
    }

    /** Every row checked before anything is written; all problems are reported at once. */
    private static Map<String, Row> validate(int year, List<Row> rows) {
        List<RowError> errors = new ArrayList<>();
        if (rows.size() > MAX_ROWS) {
            errors.add(new RowError(0, "At most " + MAX_ROWS + " rows per year (got " + rows.size() + ")"));
            throw new RejectedRows(errors);
        }
        Map<String, Row> wanted = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int n = i + 1;
            if (r == null || r.date() == null) {
                errors.add(new RowError(n, "date is missing"));
                continue;
            }
            if (r.date().getYear() != year) {
                errors.add(new RowError(n, r.date() + " is not in " + year));
                continue;
            }
            String name = r.name() == null ? "" : r.name().trim();
            if (name.isEmpty() || name.length() > MAX_NAME) {
                errors.add(new RowError(n, "name must be 1-" + MAX_NAME + " characters"));
                continue;
            }
            String state;
            try {
                state = Holiday.normaliseState(r.stateCode());
            } catch (InvalidStateCodeException e) {
                errors.add(new RowError(n, e.getMessage()));
                continue;
            }
            String key = key(r.date(), state);
            if (!seen.add(key)) {
                errors.add(new RowError(n, r.date() + (state.isEmpty() ? " (national)" : " " + state)
                        + " appears twice"));
                continue;
            }
            wanted.put(key, new Row(r.date(), state, name));
        }
        if (!errors.isEmpty()) {
            throw new RejectedRows(errors);
        }
        return wanted;
    }

    /** Running clocks a change to this day does not reach: only a day from yesterday on can be inside one. */
    private long reached(LocalDate date, Instant now) {
        Instant dayStart = date.atStartOfDay(WorkingDays.INDIA).toInstant();
        return dayStart.isBefore(now.minus(Duration.ofDays(1))) ? 0 : clocks.workingDayClocksEndingAfter(dayStart);
    }

    private static int count(List<Item> items, Change c) {
        return (int) items.stream().filter(i -> i.change() == c).count();
    }

    private static String key(LocalDate date, String state) {
        return date + "|" + state;
    }
}
