package com.loofie.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;

import com.loofie.core.identity.app.SessionAuthenticator;

/**
 * The dev login (trusting X-Dev-* headers) must never be active outside the "local" profile. Everywhere else the only
 * way in is a session cookie (F1 round A); a request with dev headers and no session is refused.
 * docs/features/f5-job-engine.md item 9, docs/features/f1-identity-round-a.md.
 */
class ProductionAuthTest {

    private final SessionAuthenticator sessions = noSessionAnywhere();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(SessionAuthenticator.class, () -> sessions)
            .withUserConfiguration(DevHeaderActorResolver.class, SessionActorResolver.class);

    @Test
    void prodNeverTrustsDevHeaders() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod")).run(ctx -> {
            assertThat(ctx).hasSingleBean(ActorResolver.class);
            assertThat(ctx).doesNotHaveBean(DevHeaderActorResolver.class);
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Dev-Role", "OPS");
            request.addHeader("X-Dev-User-Id", "0195f000-0000-7000-8000-000000000001");
            assertThatThrownBy(() -> ctx.getBean(ActorResolver.class).resolve(request))
                    .isInstanceOf(UnauthenticatedException.class);
        });
    }

    @Test
    void noProfileAtAllIsTreatedLikeProduction() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ActorResolver.class);
            assertThat(ctx).getBean(ActorResolver.class).isInstanceOf(SessionActorResolver.class);
        });
    }

    @Test
    void onlyTheLocalProfileUsesTheDevLogin() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("local")).run(ctx ->
                assertThat(ctx).getBean(ActorResolver.class).isInstanceOf(DevHeaderActorResolver.class));
    }

    private static SessionAuthenticator noSessionAnywhere() {
        SessionAuthenticator s = mock(SessionAuthenticator.class);
        when(s.actor(any())).thenThrow(new UnauthenticatedException("Log in to continue"));
        return s;
    }
}
