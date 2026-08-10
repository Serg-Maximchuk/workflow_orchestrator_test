plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "Telecom Service Integration Layer on an embedded Flowable 7 engine"

// Spring Boot 3.5.x is pinned deliberately: Flowable 7.1.0 is built against Spring Boot 3.3/3.4
// and its auto-configuration does not yet work on Spring Boot 4 (Spring Framework 7).
// Java 21 (set in the root build) for the same reason - the toolchain stays inside the range both
// projects support, even though the machine has JDK 25 installed.
val flowableVersion = "7.1.0"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Process engine only. The CMMN/DMN/app engines are pulled in later (DMN in Phase 6),
    // so the schema stays small and startup stays fast.
    implementation("org.flowable:flowable-spring-boot-starter-process:$flowableVersion")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    // In-memory database for fast BPMN process tests that do not need real Postgres.
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
