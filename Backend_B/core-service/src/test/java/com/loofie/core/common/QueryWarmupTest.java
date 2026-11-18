package com.loofie.core.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.spi.QueryInterpretationCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.loofie.core.job.domain.Assignment;
import com.loofie.core.job.infra.AssignmentRepository;
import com.loofie.core.job.infra.ShipmentRepository;
import com.loofie.core.support.IntegrationTest;

/**
 * The startup warm-up parses every repository query before traffic (round B load finding: a cold burst of "Shipped"
 * requests parsed the same query in every request while holding a connection and exhausted the pool).
 */
@IntegrationTest
class QueryWarmupTest {

    @Autowired
    QueryWarmup warmup;

    @Autowired
    EntityManagerFactory emf;

    @Autowired
    AssignmentRepository assignments;

    @Autowired
    ShipmentRepository shipments;

    @Test
    void everyRepositoryQueryIsWarmedNoneSkippedNoneFailed() {
        QueryWarmup.Result r = warmup.warm();

        // AssignmentRepository 2 + ShipmentRepository 4; JobRepository and OrderRepository have none
        assertThat(r.warmed()).isEqualTo(6);
        assertThat(r.skipped()).isEmpty();
        assertThat(r.failed()).isEmpty();
    }

    @Test
    void afterStartupTheHotShippingQueriesNeedNoNewParse() {
        QueryInterpretationCache cache = emf.unwrap(SessionFactoryImplementor.class).getQueryEngine()
                .getInterpretationCache();
        assertThat(cache.isEnabled()).isTrue();
        int before = cache.getNumberOfCachedHqlInterpretations();

        // the queries a "Shipped" request runs, with real-looking (new) arguments
        UUID job = UUID.randomUUID();
        assignments.findByJobIdAndStatus(job, Assignment.Status.ACTIVE);
        assignments.findByJobIdOrderByOpenedAt(job);
        shipments.findTopByJobIdOrderByAttemptDesc(job);
        shipments.findByJobIdOrderByAttempt(job);
        shipments.countByJobId(job);
        shipments.findByJobIdAndAttempt(job, 2);

        assertThat(cache.getNumberOfCachedHqlInterpretations())
                .as("the warm-up parsed exactly the strings real calls use").isEqualTo(before);
    }

    @Test
    void throwawayArgumentsCoverTheParameterTypesInUse() throws Exception {
        assertThat(QueryWarmup.throwawayArguments(
                AssignmentRepository.class.getMethod("findByJobIdAndStatus", UUID.class, Assignment.Status.class)))
                .containsExactly(new UUID(0, 0), Assignment.Status.values()[0]);
        assertThat(QueryWarmup.throwawayArguments(Object.class.getMethod("equals", Object.class))).isNull();
    }
}
