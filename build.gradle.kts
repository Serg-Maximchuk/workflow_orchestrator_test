// Root project holds no code. Every engine implementation is its own subproject so that
// two engines can be built, run and compared side by side without one replacing the other.
plugins {
    java
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "com.example.sil"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    // Two test lanes in every engine module, mirroring the two CI jobs:
    //   ./gradlew test            - fast unit and process tests, no Docker required
    //   ./gradlew integrationTest - everything tagged "integration", backed by Testcontainers
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    tasks.named<Test>("test") {
        useJUnitPlatform {
            excludeTags("integration")
        }
    }

    // Resolved on the project, not inside the task configuration block, where `extensions`
    // would refer to the task's own extension container.
    // Gradle 9 exposes source sets through the java extension, not as a standalone extension.
    val testSourceSet = extensions.getByType<JavaPluginExtension>().sourceSets["test"]

    val integrationTest = tasks.register<Test>("integrationTest") {
        description = "Runs tests tagged 'integration' (Testcontainers, requires a Docker daemon)."
        group = "verification"
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        shouldRunAfter(tasks.named("test"))
        useJUnitPlatform {
            includeTags("integration")
        }
    }

    tasks.named("check") {
        dependsOn(integrationTest)
    }
}
