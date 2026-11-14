package com.loofie.core.common;

import java.net.InetAddress;
import java.time.Duration;
import java.util.UUID;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Makes sure only one running instance does a given background job (outbox relay, timers, cleanup).
 *
 * <p>A lease is a row in {@code scheduler_leases}. An instance holds it until {@code expires_at};
 * it renews on every run, and another instance may take it only after it has expired, so a crashed
 * leader is replaced within one TTL. All times are the database's, so clock skew between servers
 * does not matter, and it works through connection poolers (PgBouncer) where session-level advisory
 * locks do not.
 *
 * <p>The TTL must be longer than the longest single run of the job it guards.
 */
@Component
public class LeaderLease {

    private static final Logger log = LoggerFactory.getLogger(LeaderLease.class);

    private final JdbcClient jdbc;
    private final String owner;

    @Autowired
    public LeaderLease(JdbcClient jdbc) {
        this(jdbc, defaultOwner());
    }

    LeaderLease(JdbcClient jdbc, String owner) {
        this.jdbc = jdbc;
        this.owner = owner;
    }

    /** Takes or renews the lease. Returns true if this instance holds it for the next {@code ttl}. */
    public boolean tryAcquire(String name, Duration ttl) {
        try {
            int rows = jdbc.sql("""
                    INSERT INTO scheduler_leases (name, owner, expires_at)
                    VALUES (:name, :owner, now() + make_interval(secs => :ttl))
                    ON CONFLICT (name) DO UPDATE
                        SET owner = EXCLUDED.owner, expires_at = EXCLUDED.expires_at
                        WHERE scheduler_leases.owner = EXCLUDED.owner OR scheduler_leases.expires_at < now()
                    """)
                    .param("name", name)
                    .param("owner", owner)
                    .param("ttl", (double) ttl.toMillis() / 1000)
                    .update();
            return rows == 1;
        } catch (RuntimeException e) {
            log.warn("Could not acquire lease {}: {}", name, e.toString());
            return false; // fail closed: never run a guarded job without the lease
        }
    }

    /** Gives up every lease this instance holds, so another instance can take over at once. */
    @PreDestroy
    public void releaseAll() {
        try {
            jdbc.sql("DELETE FROM scheduler_leases WHERE owner = :owner").param("owner", owner).update();
        } catch (RuntimeException e) {
            log.debug("Could not release leases on shutdown: {}", e.toString());
        }
    }

    public String owner() {
        return owner;
    }

    private static String defaultOwner() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        String id = host + "/" + UUID.randomUUID();
        return id.length() > 128 ? id.substring(id.length() - 128) : id;
    }
}
