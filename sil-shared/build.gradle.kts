plugins {
    `java-library`
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "Engine-agnostic building blocks: TMF APIs, supplier adapter, idempotency, correlation"

// A library, not an application: every engine module depends on this one, so the same HTTP
// contract and the same supplier adapter are shared instead of being reimplemented per engine.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") { enabled = false }
tasks.named<Jar>("jar") { enabled = true }

val resilience4jVersion = "2.4.0"
val lombokVersion = "1.18.46"

dependencies {
    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")

    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-oauth2-client")
    api("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
    // Spring Boot 4 moved Liquibase auto-configuration out of spring-boot-autoconfigure
    // into its own starter; liquibase-core alone is on the classpath but never runs.
    api("org.springframework.boot:spring-boot-starter-liquibase")

    // Retry, timeout and circuit breaker around the supplier calls. resilience4j-spring-boot4 is
    // the variant built for Spring Boot 4; the -spring-boot3 artifact targets the older baseline.
    api("io.github.resilience4j:resilience4j-spring-boot4:$resilience4jVersion")
    // Spring Boot 4 dropped the aop starter; the resilience4j annotations still need a weaver
    // on the classpath for the proxies to be created.
    api("org.aspectj:aspectjweaver")
}
