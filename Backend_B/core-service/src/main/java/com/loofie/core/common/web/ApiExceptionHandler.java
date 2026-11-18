package com.loofie.core.common.web;

import java.net.URI;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.loofie.core.calendar.domain.StateCodes.InvalidStateCodeException;
import com.loofie.core.common.Ids;
import com.loofie.core.refund.domain.RefundRuleException;
import com.loofie.core.common.ServiceBusyException;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.job.domain.JobExceptions.IllegalTransitionException;
import com.loofie.core.job.domain.JobExceptions.InvalidQueryException;
import com.loofie.core.job.domain.JobExceptions.JobNotFoundException;
import com.loofie.core.job.domain.JobExceptions.StaleJobVersionException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.security.UnauthenticatedException;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Maps errors to RFC 9457 problem responses with a stable {@code type} per error, and a
 * {@code requestId} that matches the logs. Unexpected errors never leak internals: the client gets
 * the id, the log gets the stack trace.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String BASE = "https://loofie.app/problems/";
    /** Seconds a client should wait before retrying after a 503. */
    private static final String RETRY_AFTER_SECONDS = "2";

    private final MeterRegistry meters;

    ApiExceptionHandler(MeterRegistry meters) {
        this.meters = meters;
    }

    @ExceptionHandler(UnauthenticatedException.class)
    ProblemDetail unauthenticated(UnauthenticatedException e) {
        return problem(HttpStatus.UNAUTHORIZED, "unauthenticated", e.getMessage());
    }

    @ExceptionHandler(ActorNotAllowedException.class)
    ProblemDetail forbidden(ActorNotAllowedException e) {
        return problem(HttpStatus.FORBIDDEN, "actor-not-allowed", e.getMessage());
    }

    @ExceptionHandler(JobNotFoundException.class)
    ProblemDetail notFound(JobNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "job-not-found", e.getMessage());
    }

    @ExceptionHandler(IllegalTransitionException.class)
    ProblemDetail illegal(IllegalTransitionException e) {
        ProblemDetail p = problem(HttpStatus.CONFLICT, "illegal-transition", e.getMessage());
        p.setProperty("currentState", e.from().name());
        p.setProperty("action", e.action().name());
        return p;
    }

    @ExceptionHandler(StaleJobVersionException.class)
    ProblemDetail stale(StaleJobVersionException e) {
        ProblemDetail p = problem(HttpStatus.CONFLICT, "stale-version", e.getMessage());
        p.setProperty("currentVersion", e.currentVersion());
        return p;
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail concurrent(ObjectOptimisticLockingFailureException e) {
        return problem(HttpStatus.CONFLICT, "concurrent-update", "The job changed while this request was running; reload and retry");
    }

    @ExceptionHandler(TransitionGuardException.class)
    ProblemDetail guard(TransitionGuardException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "rule-violated", e.getMessage());
    }

    @ExceptionHandler(Idempotency.InvalidKeyException.class)
    ProblemDetail invalidKey(Idempotency.InvalidKeyException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-idempotency-key", e.getMessage());
    }

    @ExceptionHandler(Idempotency.KeyRequiredException.class)
    ProblemDetail keyRequired(Idempotency.KeyRequiredException e) {
        return problem(HttpStatus.PRECONDITION_REQUIRED, "idempotency-key-required", e.getMessage());
    }

    @ExceptionHandler(Idempotency.KeyReusedException.class)
    ProblemDetail keyReused(Idempotency.KeyReusedException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused", e.getMessage());
    }

    @ExceptionHandler(Idempotency.InProgressException.class)
    ProblemDetail inProgress(Idempotency.InProgressException e) {
        return problem(HttpStatus.CONFLICT, "request-in-progress", e.getMessage());
    }

    /** A database rule caught a race the code did not (e.g. two creators invited at the same moment). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        log.warn("Constraint violation: {}", e.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "conflict", "This change conflicts with another one made at the same time; reload and retry");
    }

    @ExceptionHandler(InvalidQueryException.class)
    ProblemDetail invalidQuery(InvalidQueryException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-query", e.getMessage());
    }

    @ExceptionHandler(RefundRuleException.class)
    ProblemDetail refundRule(RefundRuleException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "refund-rule", e.getMessage());
    }

    /** A year import with wrong rows: nothing was applied; every bad row is listed. */
    @ExceptionHandler(com.loofie.core.calendar.app.HolidayYearImport.RejectedRows.class)
    ProblemDetail holidayRows(com.loofie.core.calendar.app.HolidayYearImport.RejectedRows e) {
        ProblemDetail p = problem(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-holiday-rows", e.getMessage());
        p.setProperty("rows", e.rows());
        return p;
    }

    @ExceptionHandler(InvalidStateCodeException.class)
    ProblemDetail invalidStateCode(InvalidStateCodeException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-state-code", e.getMessage());
    }

    /**
     * The database is overloaded or briefly unreachable, or a protective limit was hit: tell the client to
     * retry, do not pretend it is a bug.
     */
    @ExceptionHandler({CannotCreateTransactionException.class, TransientDataAccessException.class,
            org.springframework.jdbc.CannotGetJdbcConnectionException.class,
            ServiceBusyException.class})
    ResponseEntity<ProblemDetail> unavailable(Exception e) {
        log.warn("Service temporarily unavailable: {}", e.toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "temporarily-unavailable",
                        "The service is busy; retry in a few seconds"));
    }

    /** File storage or Backend A is down or not configured here: say which, and when to retry. */
    @ExceptionHandler(com.loofie.core.common.DependencyUnavailableException.class)
    ResponseEntity<ProblemDetail> dependency(com.loofie.core.common.DependencyUnavailableException e) {
        log.warn("Dependency unavailable: {}", e.toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "30")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "dependency-unavailable", e.getMessage()));
    }

    /** An upload key that is not this person's, was never uploaded, is too big or the wrong type. */
    @ExceptionHandler(com.loofie.core.storage.Uploads.UploadRefusedException.class)
    ProblemDetail uploadRefused(com.loofie.core.storage.Uploads.UploadRefusedException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "upload-refused", e.getMessage());
    }

    /** Anything else is a bug: log it with the id the client sees, never send internals back. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        UUID id = RequestIdFilter.current() != null ? RequestIdFilter.current() : Ids.newId();
        log.error("Unhandled error (requestId {})", id, e);
        ProblemDetail p = problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "Something went wrong on our side; quote the requestId if you contact support");
        p.setProperty("requestId", id.toString());
        return p;
    }

    private ProblemDetail problem(HttpStatus status, String type, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create(BASE + type));
        UUID requestId = RequestIdFilter.current();
        if (requestId != null) {
            p.setProperty("requestId", requestId.toString());
        }
        meters.counter("loofie.api.errors", "type", type).increment();
        return p;
    }
}
