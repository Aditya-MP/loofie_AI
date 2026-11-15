package com.loofie.core.job.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.loofie.core.support.IntegrationTest;

/**
 * Guards the measured fixes in docs/features/f5-job-engine.md: if someone changes the overdue
 * expression or the deadline order, the index silently stops being used and the ops list is back to a
 * full scan. Seq scans are switched off so the test proves the index CAN serve the query (with the tiny
 * test data the planner would otherwise prefer a scan either way).
 */
@IntegrationTest
class JobReadRepositoryPlanTest {

    @Autowired JdbcClient jdbc;
    @Autowired JobReadRepository repo;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void theOverdueFilterUsesTheEarliestDeadlineIndex() {
        String plan = explain("SELECT count(*) FROM jobs j WHERE " + JobReadRepository.EARLIEST_DEADLINE + " < now()");
        assertThat(plan).contains("jobs_earliest_deadline_idx");
    }

    @Test
    void keysetPagingUsesTheUpdatedAtIndex() {
        String plan = explain("SELECT j.id FROM jobs j WHERE (j.updated_at, j.id) < (now(), gen_random_uuid())"
                + " ORDER BY j.updated_at DESC, j.id DESC LIMIT 25");
        assertThat(plan).contains("jobs_updated_idx");
    }

    @Test
    void aReadStatementThatRunsTooLongIsCutOff() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            repo.limitStatementTime(Duration.ofMillis(100));
            jdbc.sql("SELECT pg_sleep(2)").query().listOfRows();
        })).isInstanceOf(TransientDataAccessException.class); // mapped to 503 + Retry-After by the API

        // The limit was for that transaction only: the pooled connection is back to normal.
        tx.executeWithoutResult(s -> jdbc.sql("SELECT pg_sleep(0.3)").query().listOfRows());
    }

    private String explain(String sql) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return tx.execute(s -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            List<String> lines = jdbc.sql("EXPLAIN " + sql).query(String.class).list();
            return String.join("\n", lines);
        });
    }
}
