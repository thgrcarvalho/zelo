package io.github.thgrcarvalho.zelo;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Postgres-backed integration test. Bundles {@link SpringBootTest} and
 * the {@code -DrunIntegrationTests=true} gate so the default {@code ./gradlew test}
 * stays fast and Docker-free.
 *
 * <p>Apply this to the concrete test class and extend {@link AbstractIntegrationTest}
 * for the shared (singleton) Postgres container. The gate must sit on the concrete class:
 * JUnit does not evaluate {@code @EnabledIf*} conditions inherited from a superclass
 * for container-level skipping, so it lives here as a directly-applied
 * meta-annotation rather than on the base class.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
public @interface IntegrationTest {
}
