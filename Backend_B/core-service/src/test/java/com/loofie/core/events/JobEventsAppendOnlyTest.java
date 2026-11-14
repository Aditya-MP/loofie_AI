package com.loofie.core.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.loofie.core.job.app.JobTransitionService;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.JobFixtures.Seeded;

@IntegrationTest
class JobEventsAppendOnlyTest {

    @Autowired JobTransitionService engine;
    @Autowired JobFixtures fixtures;
    @Autowired JdbcClient jdbc;

    @Test
    void updateDeleteAndTruncateAreRejectedByTheDatabase() {
        Seeded seeded = fixtures.jobIn(JobStatus.AWAITING_PAYMENT);
        engine.transition(seeded.jobId(), JobAction.PAYMENT_CONFIRMED, Actor.system(), null);
        UUID jobId = seeded.jobId();

        assertThatThrownBy(() -> jdbc.sql("UPDATE job_events SET to_state = 'APPROVED' WHERE job_id = ?").param(jobId).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM job_events WHERE job_id = ?").param(jobId).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE job_events").update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("append-only");

        assertThat(fixtures.eventCount(jobId)).isEqualTo(1);
    }
}
