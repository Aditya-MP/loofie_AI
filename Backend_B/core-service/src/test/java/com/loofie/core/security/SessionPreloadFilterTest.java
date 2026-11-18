package com.loofie.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.loofie.core.identity.app.SessionAuthenticator;

/** A busy database during the session lookup answers 503 + Retry-After, never a 500 (round B load test finding). */
class SessionPreloadFilterTest {

    @Test
    void poolExhaustedDuringSessionLookupIs503() throws Exception {
        SessionAuthenticator auth = mock(SessionAuthenticator.class);
        when(auth.token(any())).thenReturn("some-session-token-value");
        when(auth.current(any())).thenThrow(new CannotGetJdbcConnectionException("pool exhausted"));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SessionPreloadFilter(auth).doFilter(new MockHttpServletRequest("POST", "/api/core/x"), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("2");
        assertThat(response.getContentAsString()).contains("temporarily-unavailable");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void noCookieMeansNoLookup() throws Exception {
        SessionAuthenticator auth = mock(SessionAuthenticator.class);
        FilterChain chain = mock(FilterChain.class);
        new SessionPreloadFilter(auth).doFilter(new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(),
                chain);
        verify(auth, never()).current(any());
        verify(chain).doFilter(any(), any());
    }
}
