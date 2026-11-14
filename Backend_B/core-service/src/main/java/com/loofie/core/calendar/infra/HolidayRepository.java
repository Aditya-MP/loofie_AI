package com.loofie.core.calendar.infra;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.loofie.core.calendar.domain.Holiday;

/** SQL for the {@code holidays} table. State codes arrive already normalised (see {@link Holiday#normaliseState}). */
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

    /** Adds a holiday, or renames it if that date and state already exist. Returns true when it was new. */
    public boolean upsert(LocalDate date, String state, String name, UUID by) {
        int inserted = jdbc.sql("""
                INSERT INTO holidays (holiday_date, state_code, name, created_by) VALUES (:date, :state, :name, :by)
                ON CONFLICT (holiday_date, state_code) DO NOTHING
                """)
                .param("date", date).param("state", state).param("name", name).param("by", by)
                .update();
        if (inserted == 0) {
            jdbc.sql("UPDATE holidays SET name = :name WHERE holiday_date = :date AND state_code = :state")
                    .param("date", date).param("state", state).param("name", name)
                    .update();
        }
        return inserted == 1;
    }

    public boolean delete(LocalDate date, String state) {
        return jdbc.sql("DELETE FROM holidays WHERE holiday_date = :date AND state_code = :state")
                .param("date", date).param("state", state)
                .update() == 1;
    }
}
