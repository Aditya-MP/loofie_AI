package com.loofie.core.job.infra;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.loofie.core.job.domain.Courier;

/** SQL for the {@code couriers} table (a dozen rows, edited by ops a few times a year). */
@Repository
public class CourierRepository {

    private static final RowMapper<Courier> ROW = (rs, n) -> new Courier(rs.getString("code"), rs.getString("name"),
            Courier.Kind.valueOf(rs.getString("kind")), rs.getString("tracking_url_pattern"), rs.getBoolean("active"),
            rs.getInt("sort_order"));
    private static final String COLUMNS = "code, name, kind, tracking_url_pattern, active, sort_order";

    private final JdbcClient jdbc;

    public CourierRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Courier> all() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM couriers ORDER BY sort_order, name").query(ROW).list();
    }

    public Optional<Courier> find(String code) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM couriers WHERE code = :code").param("code", code).query(ROW).optional();
    }

    /** For clients that still send only a courier name ("Delhivery"). */
    public Optional<Courier> findByName(String name) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM couriers WHERE lower(name) = lower(:name) AND active")
                .param("name", name.trim()).query(ROW).optional();
    }

    /** Returns true when the courier was new. */
    public boolean upsert(Courier c, UUID by, Instant now) {
        boolean existed = find(c.code()).isPresent();
        jdbc.sql("""
                INSERT INTO couriers (code, name, kind, tracking_url_pattern, active, sort_order, updated_by, updated_at)
                VALUES (:code, :name, :kind, :pattern, :active, :sort, :by, :now)
                ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, kind = EXCLUDED.kind,
                    tracking_url_pattern = EXCLUDED.tracking_url_pattern, active = EXCLUDED.active,
                    sort_order = EXCLUDED.sort_order, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                """)
                .param("code", c.code()).param("name", c.name()).param("kind", c.kind().name())
                .param("pattern", c.trackingUrlPattern()).param("active", c.active()).param("sort", c.sortOrder())
                .param("by", by).param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
        return !existed;
    }
}
