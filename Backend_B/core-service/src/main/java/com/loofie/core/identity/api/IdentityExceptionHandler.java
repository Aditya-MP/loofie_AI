package com.loofie.core.identity.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.loofie.core.common.web.RequestIdFilter;
import com.loofie.core.identity.domain.Contacts.InvalidContactException;
import com.loofie.core.identity.domain.IdentityExceptions.AccountNotFoundException;
import com.loofie.core.identity.domain.IdentityExceptions.AccountSuspendedException;
import com.loofie.core.identity.domain.IdentityExceptions.CodeExpiredException;
import com.loofie.core.identity.domain.IdentityExceptions.ContactTakenException;
import com.loofie.core.identity.domain.IdentityExceptions.DeliveryUnavailableException;
import com.loofie.core.identity.domain.IdentityExceptions.InvalidCodeException;
import com.loofie.core.identity.domain.IdentityExceptions.InvalidRequestException;
import com.loofie.core.identity.domain.IdentityExceptions.RateLimitedException;

import io.micrometer.core.instrument.MeterRegistry;

/** Login and account errors as problem responses. Runs before the general handler (which catches everything else). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class IdentityExceptionHandler {

    private static final String BASE = "https://loofie.app/problems/";
    private final MeterRegistry meters;

    IdentityExceptionHandler(MeterRegistry meters) {
        this.meters = meters;
    }

    @ExceptionHandler(InvalidCodeException.class)
    ProblemDetail invalidCode(InvalidCodeException e) {
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "invalid-code", e.getMessage());
        if (e.attemptsLeft() >= 0) {
            p.setProperty("attemptsLeft", e.attemptsLeft());
        }
        return p;
    }

    @ExceptionHandler(CodeExpiredException.class)
    ProblemDetail expired(CodeExpiredException e) {
        return problem(HttpStatus.BAD_REQUEST, "code-expired", e.getMessage());
    }

    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<ProblemDetail> limited(RateLimitedException e) {
        long seconds = Math.max(1, e.retryAfter().toSeconds());
        ProblemDetail p = problem(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", e.getMessage());
        p.setProperty("retryAfterSeconds", seconds);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                .body(p);
    }

    @ExceptionHandler(DeliveryUnavailableException.class)
    ResponseEntity<ProblemDetail> delivery(DeliveryUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "60")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "delivery-unavailable", e.getMessage()));
    }

    @ExceptionHandler(AccountSuspendedException.class)
    ProblemDetail suspended(AccountSuspendedException e) {
        return problem(HttpStatus.FORBIDDEN, "account-suspended", e.getMessage());
    }

    @ExceptionHandler({InvalidRequestException.class, InvalidContactException.class})
    ProblemDetail invalid(RuntimeException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-request", e.getMessage());
    }

    @ExceptionHandler(ContactTakenException.class)
    ProblemDetail taken(ContactTakenException e) {
        return problem(HttpStatus.CONFLICT, "contact-taken", e.getMessage());
    }

    @ExceptionHandler(com.loofie.core.identity.domain.IdentityExceptions.GstinTakenException.class)
    ProblemDetail gstinTaken(RuntimeException e) {
        return problem(HttpStatus.CONFLICT, "gstin-taken", e.getMessage());
    }

    @ExceptionHandler(com.loofie.core.identity.domain.IdentityExceptions.CompanyNotFoundException.class)
    ProblemDetail companyNotFound(RuntimeException e) {
        return problem(HttpStatus.NOT_FOUND, "company-not-found", e.getMessage());
    }

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail notFound(AccountNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "account-not-found", e.getMessage());
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
