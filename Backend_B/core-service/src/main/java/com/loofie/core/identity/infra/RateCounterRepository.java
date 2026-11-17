package com.loofie.core.identity.infra;

import static com.loofie.core.identity.infra.UserRepository.ts;

import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Fixed-window counters in Postgres, so every instance shares one count. */
@Repository
public class RateCounterRepository {

    private final JdbcClient jdbc;

    public RateCounterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Counts one hit if the window is still under {@code limit}, in one statement (no read-then-write race between
     * instances). Refused hits are not counted, so a blocked caller does not extend its own block.
     *
     * @return true if allowed
     */
    public boolean tryHit(String key, Instant windowStart, int limit) {
        return jdbc.sql("""
                INSERT INTO rate_counters AS rc (key, window_start, count) VALUES (:key, :w, 1)
                ON CONFLICT (key, window_start) DO UPDATE SET count = rc.count + 1 WHERE rc.count < :limit
                RETURNING count""")
                .param("key", key).param("w", ts(windowStart)).param("limit", limit)
                .query(Integer.class).optional().isPresent();
    }

    /** Gives a hit back (the message could not be sent, so it should not use up the person's allowance). */
    public void refund(String key, Instant windowStart) {
        jdbc.sql("UPDATE rate_counters SET count = count - 1 WHERE key = :key AND window_start = :w AND count > 0")
                .param("key", key).param("w", ts(windowStart)).update();
    }
}
