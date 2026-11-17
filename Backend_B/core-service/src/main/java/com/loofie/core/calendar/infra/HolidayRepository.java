package com.loofie.core.calendar.infra;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.calendar.domain.Holiday;
import com.loofie.core.calendar.domain.HolidayChange;
import com.loofie.core.calendar.domain.HolidayChange.Action;
import com.loofie.core.calendar.domain.HolidayChange.Outcome;

/**
 * SQL for {@code holidays} and its append-only history {@code holiday_changes}. Every change writes its history row
 * in the same transaction. State codes arrive already validated (see {@link Holiday#normaliseState}).
 */
@Repository
public class HolidayRepository {

    private final JdbcClient jdbc;

    public HolidayRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** National holidays plus those of {@code state}, between the two dates inclusive. */
    public Set<LocalDate> closedDates(LocalDate from, LocalDate to, String state) {
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

    public long countNational(LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT count(*) FROM holidays WHERE state_code = '' AND holiday_date BETWEEN :from AND :to")
                .param("from", from).param("to", to).query(Long.class).single();
    }

    public List<Holiday> between(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT holiday_date, state_code, name FROM holidays
                WHERE holiday_date BETWEEN :from AND :to ORDER BY holiday_date, state_code
                """)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new Holiday(rs.getObject("holiday_date", LocalDate.class), rs.getString("state_code"),
                        rs.getString("name")))
                .list();
    }

    /** Adds a holiday or renames it. A rename to the same name changes nothing and is not recorded. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome upsert(LocalDate date, String state, String name, UUID by, Instant now) {
        Optional<String> current = lockName(date, state);
        if (current.isEmpty()) {
            int inserted = jdbc.sql("""
                    INSERT INTO holidays (holiday_date, state_code, name, created_by, created_at)
                    VALUES (:date, :state, :name, :by, :now)
                    ON CONFLICT (holiday_date, state_code) DO NOTHING
                    """)
                    .param("date", date).param("state", state).param("name", name).param("by", by)
                    .param("now", utc(now))
                    .update();
            if (inserted == 1) {
                record(now, by, Action.INSERT, date, state, null, name);
                return Outcome.CREATED;
            }
            current = lockName(date, state); // another request inserted it a moment ago
        }
        String old = current.orElseThrow();
        if (old.equals(name)) {
            return Outcome.UNCHANGED;
        }
        jdbc.sql("UPDATE holidays SET name = :name WHERE holiday_date = :date AND state_code = :state")
                .param("date", date).param("state", state).param("name", name)
                .update();
        record(now, by, Action.RENAME, date, state, old, name);
        return Outcome.RENAMED;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean delete(LocalDate date, String state, UUID by, Instant now) {
        Optional<String> deleted = jdbc.sql("""
                DELETE FROM holidays WHERE holiday_date = :date AND state_code = :state RETURNING name
                """)
                .param("date", date).param("state", state)
                .query(String.class).optional();
        deleted.ifPresent(name -> record(now, by, Action.DELETE, date, state, name, null));
        return deleted.isPresent();
    }

    /** History of holidays falling between the two dates, oldest change first. */
    public List<HolidayChange> changes(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT changed_at, changed_by, action, holiday_date, state_code, old_name, new_name
                FROM holiday_changes WHERE holiday_date BETWEEN :from AND :to ORDER BY id
                """)
                .param("from", from).param("to", to)
                .query((rs, n) -> new HolidayChange(rs.getObject("changed_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("changed_by", UUID.class), Action.valueOf(rs.getString("action")),
                        rs.getObject("holiday_date", LocalDate.class), rs.getString("state_code"),
                        rs.getString("old_name"), rs.getString("new_name")))
                .list();
    }

    /** Serialises imports of one year (transaction-scoped). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockYear(int year) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('holiday-year'), :year)").param("year", year)
                .query().singleRow();
    }

    private Optional<String> lockName(LocalDate date, String state) {
        return jdbc.sql("SELECT name FROM holidays WHERE holiday_date = :date AND state_code = :state FOR UPDATE")
                .param("date", date).param("state", state)
                .query(String.class).optional();
    }

    private void record(Instant now, UUID by, Action action, LocalDate date, String state, String oldName,
                        String newName) {
        jdbc.sql("""
                INSERT INTO holiday_changes (changed_at, changed_by, action, holiday_date, state_code, old_name, new_name)
                VALUES (:now, :by, :action, :date, :state, :old, :new)
                """)
                .param("now", utc(now)).param("by", by).param("action", action.name()).param("date", date)
                .param("state", state).param("old", oldName).param("new", newName)
                .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
