package com.loofie.core.creator.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.loofie.core.common.web.RequestIdFilter;
import com.loofie.core.creator.domain.CreatorExceptions.CreatorNotFoundException;
import com.loofie.core.creator.domain.CreatorExceptions.EvidenceNotFoundException;

import io.micrometer.core.instrument.MeterRegistry;

/** Creator passport errors as problem responses (the general handler covers 403, 422, 503). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class CreatorExceptionHandler {

    private final MeterRegistry meters;

    CreatorExceptionHandler(MeterRegistry meters) {
        this.meters = meters;
    }

    @ExceptionHandler(CreatorNotFoundException.class)
    ProblemDetail creator(CreatorNotFoundException e) {
        return problem("creator-not-found", e.getMessage());
    }

    @ExceptionHandler(EvidenceNotFoundException.class)
    ProblemDetail evidence(EvidenceNotFoundException e) {
        return problem("evidence-not-found", e.getMessage());
    }

    private ProblemDetail problem(String type, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail);
        p.setType(URI.create("https://loofie.app/problems/" + type));
        UUID requestId = RequestIdFilter.current();
        if (requestId != null) {
            p.setProperty("requestId", requestId.toString());
        }
        meters.counter("loofie.api.errors", "type", type).increment();
        return p;
    }
}
