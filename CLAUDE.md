# Project rules

## Language

- **Everything written to a file is always English** — code, comments, docs, README, commit
  messages, BPMN element names, log messages. No exceptions, regardless of the language of the
  request that triggered the change.

## Git

- Remote: `https://github.com/Serg-Maximchuk/workflow_orchestrator_test`
- **Atomic commits.** One commit does one thing: a single feature, fix, refactor or doc change.
  Do not bundle unrelated changes; split them into separate commits instead.
- Conventional-commit style subjects (`feat:`, `fix:`, `test:`, `docs:`, `build:`, `chore:`,
  `ci:`), scoped by module where it helps (`feat(flowable): ...`).
- **Never push without explicit permission from the user.** Committing locally is fine; every
  `git push` needs to be asked for and approved first.

## Engines

- Workflow engines are added **side by side**, never by rewriting one into another. Each engine
  lives in its own module (`engine-flowable`, later `engine-camunda8`, ...) so that coverage of
  every engine stays visible in the working tree, not only in git history, and the
  implementations can be compared directly.

## Code style

- No hand-written getters/setters. Use `record` where the type is immutable data (DTOs,
  `@ConfigurationProperties`, value objects) and Lombok where the language cannot (JPA entities:
  `@Getter`, `@NoArgsConstructor(access = PROTECTED)`, `@Builder` for long argument lists).
- Never `@Data` or `@EqualsAndHashCode` on a JPA entity — identity is the primary key, and
  field-based equality breaks once a mutable field changes while the instance is in a collection.

## Build

- Java 25 toolchain, Spring Boot 4.1.x, Flowable 8.x (the Flowable line that supports Spring Boot 4).
- Two test lanes: `./gradlew test` (fast, no Docker) and `./gradlew integrationTest`
  (Testcontainers, tagged `integration`).
