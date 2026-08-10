# Project rules

## Language

- Conversation may be in Ukrainian or English, whichever the user prefers at the moment.
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

## Build

- Java 21 toolchain, Spring Boot 3.5.x (Flowable 7.1.0 does not support Spring Boot 4 yet).
- Two test lanes: `./gradlew test` (fast, no Docker) and `./gradlew integrationTest`
  (Testcontainers, tagged `integration`).
