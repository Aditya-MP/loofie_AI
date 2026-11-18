package com.loofie.core.common;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.support.Repositories;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs every Spring Data query method once at startup, before the app reports ready, so Hibernate has parsed them.
 *
 * <p>Why (F1 round B load test): a derived query such as {@code findByJobIdAndStatus} is parsed by Hibernate the first
 * time it is called, and the parse is cached for the whole app. On a cold app, a burst of requests made every request
 * parse the same query at once, inside its transaction and so holding a database connection; the parser serialises on a
 * shared lock, the pool (20) filled up, and for 5-7 s after a start other requests got 503. Spring Boot reports
 * {@code /actuator/health/readiness} UP only after all {@link ApplicationRunner}s finish, so no traffic is routed before
 * this is done.
 *
 * <p>The calls use throwaway arguments (a zero UUID, the first enum constant, ...) in a read-only transaction, so they
 * find nothing and change nothing. Methods are discovered, not listed, so a new repository method is covered without
 * anyone remembering. A failure is logged and never stops startup: the only cost is the slow first call it would have
 * prevented.
 */
@Component
@ConditionalOnProperty(name = "loofie.warmup.enabled", havingValue = "true", matchIfMissing = true)
public class QueryWarmup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QueryWarmup.class);

    /** What one warm-up did; {@code skipped} lists methods whose parameter types have no throwaway value. */
    public record Result(int warmed, List<String> skipped, List<String> failed, long millis) {
    }

    private final ListableBeanFactory beans;
    private final TransactionTemplate readOnly;

    public QueryWarmup(ListableBeanFactory beans, PlatformTransactionManager txManager) {
        this.beans = beans;
        this.readOnly = new TransactionTemplate(txManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public void run(ApplicationArguments args) {
        Result r = warm();
        log.info("Query warm-up: {} repository queries parsed in {} ms (skipped {}, failed {})", r.warmed(), r.millis(),
                r.skipped(), r.failed());
    }

    public Result warm() {
        long start = System.nanoTime();
        int warmed = 0;
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Repositories repositories = new Repositories(beans);
        for (Class<?> domainType : repositories) {
            Object repository = repositories.getRepositoryFor(domainType).orElse(null);
            if (repository == null) {
                continue;
            }
            for (Method method : repositories.getRequiredRepositoryInformation(domainType).getQueryMethods()) {
                String name = method.getDeclaringClass().getSimpleName() + "." + method.getName();
                Object[] arguments = throwawayArguments(method);
                if (arguments == null) {
                    skipped.add(name);
                    continue;
                }
                try {
                    readOnly.executeWithoutResult(status -> {
                        try {
                            method.invoke(repository, arguments);
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException(e.getCause() != null ? e.getCause() : e);
                        }
                    });
                    warmed++;
                } catch (RuntimeException e) {
                    log.warn("Query warm-up of {} failed (first real call will parse it instead): {}", name,
                            e.toString());
                    failed.add(name);
                }
            }
        }
        return new Result(warmed, List.copyOf(skipped), List.copyOf(failed), (System.nanoTime() - start) / 1_000_000);
    }

    /** @return one throwaway value per parameter, or null when a parameter type has none */
    static Object[] throwawayArguments(Method method) {
        Class<?>[] types = method.getParameterTypes();
        Object[] values = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Object v = throwaway(types[i]);
            if (v == null) {
                return null;
            }
            values[i] = v;
        }
        return values;
    }

    private static Object throwaway(Class<?> t) {
        if (t == UUID.class) {
            return new UUID(0, 0);
        }
        if (t == String.class) {
            return "";
        }
        if (t == int.class || t == Integer.class) {
            return 0;
        }
        if (t == long.class || t == Long.class) {
            return 0L;
        }
        if (t == boolean.class || t == Boolean.class) {
            return Boolean.FALSE;
        }
        if (t == Instant.class) {
            return Instant.EPOCH;
        }
        if (t == Pageable.class) {
            return Pageable.ofSize(1);
        }
        if (t.isEnum() && t.getEnumConstants().length > 0) {
            return t.getEnumConstants()[0];
        }
        return null;
    }
}
