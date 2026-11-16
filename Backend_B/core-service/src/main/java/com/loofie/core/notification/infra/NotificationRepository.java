package com.loofie.core.notification.infra;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** SQL for {@code notifications}: one row per recipient inbox, ids and codes only. */
@Repository
public class NotificationRepository {

    /** Who an inbox belongs to: a brand org, a creator, or the shared ops / founder inbox (id null). */
    public record Inbox(String type, UUID id) {
    }

    public record Row(UUID id, String kind, UUID jobId, String payload, Instant createdAt, Instant readAt) {
    }

    private final JdbcClient jdbc;

    public NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(UUID id, Inbox to, String kind, UUID jobId, String payloadJson, Instant at) {
        jdbc.sql("""
                INSERT INTO notifications (id, recipient_type, recipient_id, kind, job_id, payload, created_at)
                VALUES (:id, :type, :rid, :kind, :job, CAST(:payload AS jsonb), :at)
                """)
                .param("id", id).param("type", to.type()).param("rid", to.id()).param("kind", kind)
                .param("job", jobId).param("payload", payloadJson).param("at", utc(at))
                .update();
    }

    /** Newest first, from any of the caller's inboxes, older than {@code before} (keyset paging). */
    public List<Row> list(List<Inbox> inboxes, boolean unreadOnly, Instant before, int limit) {
        StringBuilder where = new StringBuilder("(");
        for (int i = 0; i < inboxes.size(); i++) {
            if (i > 0) {
                where.append(" OR ");
            }
            where.append(inboxes.get(i).id() == null
                    ? "(recipient_type = :t" + i + " AND recipient_id IS NULL)"
                    : "(recipient_type = :t" + i + " AND recipient_id = :r" + i + ")");
        }
        where.append(")");
        var spec = jdbc.sql("SELECT id, kind, job_id, payload::text AS payload, created_at, read_at FROM notifications"
                + " WHERE " + where + (unreadOnly ? " AND read_at IS NULL" : "")
                + (before != null ? " AND created_at < :before" : "")
                + " ORDER BY created_at DESC LIMIT :limit");
        for (int i = 0; i < inboxes.size(); i++) {
            spec = spec.param("t" + i, inboxes.get(i).type());
            if (inboxes.get(i).id() != null) {
                spec = spec.param("r" + i, inboxes.get(i).id());
            }
        }
        if (before != null) {
            spec = spec.param("before", utc(before));
        }
        return spec.param("limit", limit)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("kind"),
                        rs.getObject("job_id", UUID.class), rs.getString("payload"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("read_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("read_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** Marks it read if it is in one of the caller's inboxes. False when it is not theirs (or does not exist). */
    public boolean markRead(UUID id, List<Inbox> inboxes, Instant at) {
        for (Inbox box : inboxes) {
            int n = jdbc.sql("UPDATE notifications SET read_at = coalesce(read_at, :at) WHERE id = :id"
                            + " AND recipient_type = :type AND " + (box.id() == null ? "recipient_id IS NULL" : "recipient_id = :rid"))
                    .param("at", utc(at)).param("id", id).param("type", box.type())
                    .param("rid", box.id())
                    .update();
            if (n == 1) {
                return true;
            }
        }
        return false;
    }

    private static OffsetDateTime utc(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
