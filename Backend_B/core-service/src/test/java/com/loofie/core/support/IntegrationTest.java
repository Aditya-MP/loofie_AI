package com.loofie.core.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full app against PostgreSQL (schema core_test), a fake Backend A ({@link FakeAiService}) and a
 * controllable clock. All integration tests share this one Spring context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@ActiveProfiles({"local", "test"})
@Import(TestSupportConfig.class)
public @interface IntegrationTest {
}
