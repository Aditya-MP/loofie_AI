package com.loofie.core.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.loofie.core.common.web.RequestIdFilter;
import com.loofie.core.identity.app.SessionAuthenticator;

/**
 * Looks up the session once, before the CSRF check needs it. Security filters run outside the MVC error handling, so
 * without this a database that is briefly busy (pool exhausted) during the session lookup would reach the client as a
 * 500. Here it becomes the same 503 + Retry-After the rest of the API answers (found by the round B load test).
 */
class SessionPreloadFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionPreloadFilter.class);

    private final SessionAuthenticator authenticator;

    SessionPreloadFilter(SessionAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (authenticator.token(request) != null) {
            try {
                authenticator.current(request); // cached on the request for the CSRF check and the controller
            } catch (DataAccessException e) {
                log.warn("Session lookup failed, answering 503: {}", e.toString());
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setHeader(HttpHeaders.RETRY_AFTER, "2");
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                String id = RequestIdFilter.current() == null ? "" : ",\"requestId\":\"" + RequestIdFilter.current()
                        + "\"";
                response.getWriter().write("{\"type\":\"https://loofie.app/problems/temporarily-unavailable\","
                        + "\"title\":\"Service Unavailable\",\"status\":503,\"detail\":\"The service is busy; retry in "
                        + "a few seconds\"" + id + "}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
