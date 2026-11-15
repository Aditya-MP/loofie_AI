package com.loofie.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The dev login (trusting X-Dev-* headers) must never be active outside the "local" profile. In "prod"
 * every request is refused until real authentication (F1) replaces it. docs/features/f5-job-engine.md item 9.
 */
class ProductionAuthTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DevHeaderActorResolver.class, DenyAllActorResolver.class);

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
            assertThat(ctx).getBean(ActorResolver.class).isInstanceOf(DenyAllActorResolver.class);
        });
    }

    @Test
    void onlyTheLocalProfileUsesTheDevLogin() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("local")).run(ctx ->
                assertThat(ctx).getBean(ActorResolver.class).isInstanceOf(DevHeaderActorResolver.class));
    }
}
