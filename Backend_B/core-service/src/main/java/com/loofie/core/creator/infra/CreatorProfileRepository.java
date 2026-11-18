package com.loofie.core.creator.infra;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.loofie.core.creator.domain.CreatorProfile;
import com.loofie.core.creator.domain.CreatorProfile.Format;
import com.loofie.core.creator.domain.CreatorProfile.InstagramStatus;
import com.loofie.core.creator.domain.CreatorProfile.Level;
import com.loofie.core.creator.domain.CreatorProfile.MarketplaceStatus;
import com.loofie.core.creator.domain.MetricValue;

/** creator_profiles and creator_metrics. */
@Repository
public class CreatorProfileRepository {

    private static final String COLUMNS = """
            user_id, display_name, city, state, languages, categories, formats, rate_per_reel_rupees, partnership_ads,
            turnaround_days, bio, sample_links, instagram_handle, instagram_status, instagram_insights_allowed,
            instagram_checked_at, level, level_reason, marketplace_status, marketplace_reason, completed_at,
            created_at, updated_at, version""";

    private final JdbcClient jdbc;

    public CreatorProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CreatorProfile> find(UUID creatorId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM creator_profiles WHERE user_id = :id").param("id", creatorId)
                .query(CreatorProfileRepository::map).optional();
    }

    /** Locks her row: profile saves, completion and ops changes are serialised per creator. */
    public Optional<CreatorProfile> findForUpdate(UUID creatorId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM creator_profiles WHERE user_id = :id FOR UPDATE")
                .param("id", creatorId).query(CreatorProfileRepository::map).optional();
    }

    /** Creates the empty row the first time; a no-op afterwards (two first requests at once are fine). */
    public void insertIfAbsent(UUID creatorId, Instant now) {
        jdbc.sql("""
                INSERT INTO creator_profiles (user_id, created_at, updated_at) VALUES (:id, :now, :now)
                ON CONFLICT (user_id) DO NOTHING""").param("id", creatorId).param("now", ts(now)).update();
    }

    public void saveForm(CreatorProfile p) {
        jdbc.sql("""
                UPDATE creator_profiles SET display_name = :name, city = :city, state = :state,
                    languages = :languages, categories = :categories, formats = :formats, rate_per_reel_rupees = :rate,
                    partnership_ads = :ads, turnaround_days = :days, bio = :bio, sample_links = :links,
                    instagram_handle = :handle, updated_at = :now, version = version + 1
                WHERE user_id = :id""")
                .param("id", p.creatorId()).param("name", p.displayName()).param("city", p.city())
                .param("state", p.state()).param("languages", p.languages().toArray(String[]::new))
                .param("categories", p.categories().toArray(String[]::new))
                .param("formats", p.formats().stream().map(Enum::name).toArray(String[]::new))
                .param("rate", p.ratePerReelRupees()).param("ads", p.partnershipAds())
                .param("days", p.turnaroundDays()).param("bio", p.bio())
                .param("links", p.sampleLinks().toArray(String[]::new)).param("handle", p.instagramHandle())
                .param("now", ts(p.updatedAt())).update();
    }

    public void markCompleted(UUID creatorId, Instant now) {
        jdbc.sql("UPDATE creator_profiles SET completed_at = :now, updated_at = :now WHERE user_id = :id "
                        + "AND completed_at IS NULL")
                .param("id", creatorId).param("now", ts(now)).update();
    }

    /** What Backend A last said about her Instagram (handle only when it is verified by them). */
    public void setInstagram(UUID creatorId, InstagramStatus status, String verifiedHandle, Boolean insightsAllowed,
                             Instant now) {
        jdbc.sql("""
                UPDATE creator_profiles SET instagram_status = :status,
                    instagram_handle = COALESCE(:handle, instagram_handle),
                    instagram_insights_allowed = :insights, instagram_checked_at = :now, updated_at = :now
                WHERE user_id = :id""")
                .param("id", creatorId).param("status", status.name()).param("handle", verifiedHandle)
                .param("insights", insightsAllowed).param("now", ts(now)).update();
    }

    public void setLevel(UUID creatorId, Level level, String reason, Instant now) {
        jdbc.sql("UPDATE creator_profiles SET level = :level, level_reason = :reason, updated_at = :now, "
                        + "version = version + 1 WHERE user_id = :id")
                .param("id", creatorId).param("level", level.name()).param("reason", reason).param("now", ts(now))
                .update();
    }

    public void setMarketplace(UUID creatorId, MarketplaceStatus status, String reason, Instant now) {
        jdbc.sql("UPDATE creator_profiles SET marketplace_status = :status, marketplace_reason = :reason, "
                        + "updated_at = :now, version = version + 1 WHERE user_id = :id")
                .param("id", creatorId).param("status", status.name()).param("reason", reason)
                .param("now", ts(now)).update();
    }

    /** Stores one number with its source; a later value of the same source replaces it. */
    public void upsertMetric(UUID creatorId, MetricValue m, UUID setBy, Instant now) {
        jdbc.sql("""
                INSERT INTO creator_metrics (creator_id, metric, source, value, captured_on, set_by, updated_at)
                VALUES (:id, :metric, :source, :value, :on, :by, :now)
                ON CONFLICT (creator_id, metric, source) DO UPDATE SET value = EXCLUDED.value,
                    captured_on = EXCLUDED.captured_on, set_by = EXCLUDED.set_by, updated_at = EXCLUDED.updated_at""")
                .param("id", creatorId).param("metric", m.metric().name()).param("source", m.source().name())
                .param("value", m.value()).param("on", m.capturedOn()).param("by", setBy).param("now", ts(now))
                .update();
    }

    public List<MetricValue> metrics(UUID creatorId) {
        return jdbc.sql("SELECT metric, value, source, captured_on FROM creator_metrics WHERE creator_id = :id "
                        + "ORDER BY metric, source")
                .param("id", creatorId)
                .query((rs, n) -> new MetricValue(MetricValue.Metric.valueOf(rs.getString("metric")),
                        rs.getLong("value"), MetricValue.Source.valueOf(rs.getString("source")),
                        rs.getObject("captured_on", LocalDate.class)))
                .list();
    }

    /**
     * Account deletion: everything that says who she is goes (name, city, state, bio, links, handle, numbers); what
     * old jobs need to stay readable (level, rate, formats, categories, languages) stays, under an id only. Hidden from
     * the marketplace for good (the account cannot log in again either).
     */
    public void erase(UUID creatorId, Instant now) {
        jdbc.sql("""
                UPDATE creator_profiles SET display_name = NULL, city = NULL, state = NULL, bio = NULL,
                    sample_links = '{}', instagram_handle = NULL, instagram_status = 'NOT_CONNECTED',
                    instagram_insights_allowed = NULL, instagram_checked_at = NULL, marketplace_status = 'HIDDEN',
                    marketplace_reason = NULL, updated_at = :now, version = version + 1
                WHERE user_id = :id""").param("id", creatorId).param("now", ts(now)).update();
        jdbc.sql("DELETE FROM creator_metrics WHERE creator_id = :id").param("id", creatorId).update();
    }

    /** Passport complete and LISTED (the account side is asked of identity, which owns users). */
    public boolean listed(UUID creatorId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM creator_profiles
                               WHERE user_id = :id AND completed_at IS NOT NULL AND marketplace_status = 'LISTED')""")
                .param("id", creatorId).query(Boolean.class).single();
    }

    private static CreatorProfile map(ResultSet rs, int n) throws SQLException {
        return new CreatorProfile(rs.getObject("user_id", UUID.class), rs.getString("display_name"),
                rs.getString("city"), rs.getString("state"), strings(rs.getArray("languages")),
                strings(rs.getArray("categories")),
                strings(rs.getArray("formats")).stream().map(Format::valueOf).toList(),
                (Integer) rs.getObject("rate_per_reel_rupees"), (Boolean) rs.getObject("partnership_ads"),
                rs.getObject("turnaround_days") == null ? null : rs.getInt("turnaround_days"), rs.getString("bio"),
                strings(rs.getArray("sample_links")), rs.getString("instagram_handle"),
                InstagramStatus.valueOf(rs.getString("instagram_status")),
                (Boolean) rs.getObject("instagram_insights_allowed"), instant(rs.getTimestamp("instagram_checked_at")),
                Level.valueOf(rs.getString("level")), rs.getString("level_reason"),
                MarketplaceStatus.valueOf(rs.getString("marketplace_status")), rs.getString("marketplace_reason"),
                instant(rs.getTimestamp("completed_at")), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")), rs.getLong("version"));
    }

    private static List<String> strings(Array a) throws SQLException {
        return a == null ? List.of() : List.copyOf(Arrays.asList((String[]) a.getArray()));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    static OffsetDateTime ts(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
