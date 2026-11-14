package com.loofie.core.common.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.loofie.core.common.Ids;

/**
 * Gives every request an id: taken from {@code X-Request-Id} (or {@code X-Correlation-Id}) when the
 * caller sends a valid UUID, otherwise generated. It is put in every log line of the request (MDC
 * key {@code requestId}), returned in the {@code X-Request-Id} response header, and used as the
 * correlation id of whatever the request changes, so one id links the app, the logs and the events.
 * Non-UUID values are ignored, so callers cannot inject text into the logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String LEGACY_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID id = parse(request.getHeader(HEADER));
        if (id == null) {
            id = parse(request.getHeader(LEGACY_HEADER));
        }
        if (id == null) {
            id = Ids.newId();
        }
        MDC.put(MDC_KEY, id.toString());
        response.setHeader(HEADER, id.toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** The current request's id, or null outside a request. */
    public static UUID current() {
        return parse(MDC.get(MDC_KEY));
    }

    private static UUID parse(String value) {
        if (value == null || value.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
