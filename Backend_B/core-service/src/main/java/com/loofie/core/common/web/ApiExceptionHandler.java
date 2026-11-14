package com.loofie.core.common.web;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.job.domain.JobExceptions.IllegalTransitionException;
import com.loofie.core.job.domain.JobExceptions.JobNotFoundException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.security.UnauthenticatedException;

/** Maps domain errors to RFC 9457 problem responses with a stable {@code type} per error. */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String BASE = "https://loofie.app/problems/";

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

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail concurrent(ObjectOptimisticLockingFailureException e) {
        return problem(HttpStatus.CONFLICT, "concurrent-update", "The job changed while this request was running; reload and retry");
    }

    @ExceptionHandler(TransitionGuardException.class)
    ProblemDetail guard(TransitionGuardException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "rule-violated", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String type, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create(BASE + type));
        return p;
    }
}
