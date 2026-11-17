package com.loofie.core.security;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.loofie.core.common.web.RequestIdFilter;
import com.loofie.core.identity.app.IdentityProperties;
import com.loofie.core.identity.app.SessionAuthenticator;
import com.loofie.core.identity.app.SessionService;

/**
 * Spring Security does three jobs here (decision D1), and nothing else: CSRF, CORS and security headers. Who the
 * caller is stays with {@link ActorResolver}; every endpoint is reachable and checks its own {@code Actor}.
 *
 * <p>CSRF: app.loofie.in and api.loofie.in are the same site, so SameSite cookies alone do not stop a forged request.
 * Any state-changing request that carries a live session cookie must send {@code X-CSRF-Token}, an HMAC of the session
 * (nothing stored). Requests without a session (the OTP steps, dev headers, server-to-server) need none: there is
 * nothing to ride on.
 */
@Configuration(proxyBeanMethods = false)
class WebSecurityConfig {

    static final String CSRF_HEADER = "X-CSRF-Token";
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Bean
    SecurityFilterChain api(HttpSecurity http, SessionAuthenticator authenticator, SessionService sessions,
                            IdentityProperties props) throws Exception {
        RequestMatcher stateChangingWithSession = request -> !SAFE.contains(request.getMethod())
                && authenticator.current(request).isPresent();
        http
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(c -> c.csrfTokenRepository(new SessionCsrfTokens(authenticator, sessions))
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(stateChangingWithSession))
                .exceptionHandling(e -> e.accessDeniedHandler(csrfRefused()))
                .cors(c -> c.configurationSource(cors(props)))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(r -> r.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable());
        return http.build();
    }

    /** Only the configured web app origins may call with cookies; none configured means same-origin only. */
    static CorsConfigurationSource cors(IdentityProperties props) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!props.allowedOrigins().isEmpty()) {
            CorsConfiguration c = new CorsConfiguration();
            c.setAllowedOrigins(props.allowedOrigins());
            c.setAllowCredentials(true);
            c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
            c.setAllowedHeaders(List.of("Content-Type", CSRF_HEADER, "Idempotency-Key", "X-Request-Id"));
            c.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
            c.setMaxAge(3600L);
            source.registerCorsConfiguration("/api/**", c);
        }
        return source;
    }

    private static AccessDeniedHandler csrfRefused() {
        return (request, response, e) -> problem(response, "csrf-token-invalid",
                "Missing or wrong " + CSRF_HEADER + " header. Read it from GET /api/core/auth/me and send it with "
                        + "every change.");
    }

    private static void problem(HttpServletResponse response, String type, String detail) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String requestId = RequestIdFilter.current() == null ? "" : ",\"requestId\":\"" + RequestIdFilter.current()
                + "\"";
        response.getWriter().write("{\"type\":\"https://loofie.app/problems/" + type + "\",\"title\":\"Forbidden\","
                + "\"status\":403,\"detail\":\"" + detail + "\"" + requestId + "}");
    }

    /** The CSRF token is derived from the session; nothing is stored, nothing to save. */
    private record SessionCsrfTokens(SessionAuthenticator authenticator, SessionService sessions)
            implements CsrfTokenRepository {

        @Override
        public CsrfToken generateToken(HttpServletRequest request) {
            CsrfToken t = loadToken(request);
            // No session: protection is not required for this request; an unusable placeholder keeps the filter happy.
            return t != null ? t : new DefaultCsrfToken(CSRF_HEADER, "_csrf", "no-session");
        }

        @Override
        public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
            // derived, never stored
        }

        @Override
        public CsrfToken loadToken(HttpServletRequest request) {
            return authenticator.current(request)
                    .map(s -> (CsrfToken) new DefaultCsrfToken(CSRF_HEADER, "_csrf", sessions.csrfToken(s)))
                    .orElse(null);
        }
    }
}
