plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "Telecom Service Integration Layer on an embedded Flowable 8 engine"

// Flowable 8.0.0 is built against Spring Boot 4.0.x / Spring Framework 7, which is what makes the
// Spring Boot 4.1.0 baseline above possible. Anything on the Flowable 7.x line would force the
// build back down to Spring Boot 3.x.
val flowableVersion = "8.0.0"

dependencies {
    implementation(project(":sil-shared"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Process engine only. The CMMN/DMN/app engines are pulled in later (DMN in Phase 6),
    // so the schema stays small and startup stays fast.
    implementation("org.flowable:flowable-spring-boot-starter-process:$flowableVersion")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // In-process stand-in for the supplier APIs, so the fast lane needs no Docker.
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    // In-memory database for fast tests that do not need real Postgres.
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
