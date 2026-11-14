package com.loofie.core.security;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.loofie.core.job.domain.Actor;

/** Fail-closed default outside local development: no request is authenticated until real auth exists. */
@Component
@Profile("!local")
class DenyAllActorResolver implements ActorResolver {

    @Override
    public Actor resolve(HttpServletRequest request) {
        throw new UnauthenticatedException("Authentication is not configured");
    }
}
