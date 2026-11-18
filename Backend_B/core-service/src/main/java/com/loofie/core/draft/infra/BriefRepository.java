package com.loofie.core.draft.infra;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.loofie.core.draft.domain.Brief;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@code job_briefs}: the brief copy on each job (D1). */
@Repository
public class BriefRepository {

    public record Stored(Brief brief, String source, UUID setBy, Instant setAt) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public BriefRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<Stored> find(UUID jobId) {
        return jdbc.sql("SELECT checklist::text AS checklist, min_seconds, max_seconds, ad_rights_days, source, set_by, "
                        + "set_at FROM job_briefs WHERE job_id = :job")
                .param("job", jobId)
                .query((rs, n) -> {
                    List<Map<String, String>> items = json.readValue(rs.getString("checklist"), new TypeReference<>() {
                    });
                    Brief b = new Brief(items.stream().map(i -> new Brief.ChecklistItem(i.get("key"), i.get("label")))
                            .toList(), rs.getInt("min_seconds"), rs.getInt("max_seconds"),
                            (Integer) rs.getObject("ad_rights_days"), true);
                    return new Stored(b, rs.getString("source"), rs.getObject("set_by", UUID.class),
                            rs.getObject("set_at", OffsetDateTime.class).toInstant());
                }).optional();
    }

    public void upsert(UUID jobId, Brief brief, String source, UUID by, Instant now) {
        String checklist = json.writeValueAsString(brief.checklist().stream()
                .map(i -> Map.of("key", i.key(), "label", i.label())).toList());
        jdbc.sql("""
                INSERT INTO job_briefs (job_id, checklist, min_seconds, max_seconds, ad_rights_days, source, set_by, set_at)
                VALUES (:job, CAST(:checklist AS jsonb), :min, :max, :days, :source, :by, :now)
                ON CONFLICT (job_id) DO UPDATE SET checklist = EXCLUDED.checklist, min_seconds = EXCLUDED.min_seconds,
                    max_seconds = EXCLUDED.max_seconds, ad_rights_days = EXCLUDED.ad_rights_days,
                    source = EXCLUDED.source, set_by = EXCLUDED.set_by, set_at = EXCLUDED.set_at""")
                .param("job", jobId).param("checklist", checklist).param("min", brief.minSeconds())
                .param("max", brief.maxSeconds()).param("days", brief.adRightsDays()).param("source", source)
                .param("by", by).param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).update();
    }
}
