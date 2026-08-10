package com.example.sil.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for tests that need the real database the application runs against in production.
 * Postgres matters here beyond "a database": Flowable's job executor relies on row locking and
 * {@code SELECT ... FOR UPDATE} semantics, which is exactly what the later retry, timer and
 * recovery tests exercise.
 *
 * <p>Tagged {@code integration}, so it runs under {@code ./gradlew integrationTest} and is skipped
 * by the fast {@code ./gradlew test} task.
 *
 * <p>The container is a JVM-wide singleton rather than a JUnit {@code @Container}: it is shared by
 * every integration test class, and it is never stopped by JUnit, so the cached Spring context
 * (with the Flowable async executor inside it) always shuts down while the database is still
 * reachable. Testcontainers' Ryuk sidecar removes it when the JVM exits.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractPostgresIntegrationTest {

    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
