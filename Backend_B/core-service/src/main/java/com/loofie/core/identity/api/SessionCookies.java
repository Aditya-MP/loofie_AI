package com.loofie.core.identity.api;

import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.loofie.core.identity.app.IdentityProperties;
import com.loofie.core.identity.app.SessionService;

/**
 * The session cookie: {@code __Host-} prefixed (Secure, Path=/, no Domain, so only api.loofie.in ever receives it),
 * HttpOnly (page scripts cannot read it) and SameSite=Lax. Its Max-Age is the session's longest possible life; the
 * server decides the real expiry.
 */
@Component
class SessionCookies {

    private final IdentityProperties props;

    SessionCookies(IdentityProperties props) {
        this.props = props;
    }

    void write(HttpServletResponse response, SessionService.Issued session) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(session.token(), session.cookieMaxAge()).toString());
    }

    void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    static String ip(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null ? "unknown" : ip.length() > 45 ? ip.substring(0, 45) : ip;
    }

    static String userAgent(HttpServletRequest request) {
        return request.getHeader(HttpHeaders.USER_AGENT);
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(props.cookieName(), value).httpOnly(true).secure(props.cookieSecure())
                .sameSite("Lax").path("/").maxAge(maxAge).build();
    }
}
