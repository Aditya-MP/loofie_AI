package com.loofie.core.security;

import jakarta.servlet.http.HttpServletRequest;

import com.loofie.core.job.domain.Actor;

/**
 * Turns an HTTP request into the {@link Actor} the domain works with. This is the only place
 * that knows how callers are authenticated (F1: the session cookie); it replaced the implementation,
 * not the controllers or the domain.
 */
public interface ActorResolver {

    /** @throws UnauthenticatedException if the caller cannot be identified */
    Actor resolve(HttpServletRequest request);
}
