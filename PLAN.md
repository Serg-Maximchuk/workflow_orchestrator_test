# Service Integration Layer (SIL) — a learning project built around a workflow engine

Goal: in roughly 2–3 weeks of evening work, cover **every** orchestration topic listed in the
Aimprosoft vacancy — BPMN processes, timers, persisted state, retries, compensation, recovery after
restart, idempotency, correlation IDs, async/event-driven integration, sagas, OpenAPI, and CI tests.

---

## 0. Engine choice

**Primary: Flowable 8, embedded in Spring Boot.**

Why this one:
- The vacancy explicitly asks to *"embed and configure a workflow engine"* and lists *"experience
  embedding or operating a workflow engine as part of a Java application"*. Flowable is a single
  dependency: process state lives in the same PostgreSQL database, inside the same transaction as
  your business data. Zero extra infrastructure.
- Its BPMN 2.0 support is nearly identical to Camunda 7/8 — timers, boundary events, compensation,
  message correlation, incidents all transfer 1:1. You learn BPMN, not a vendor.
- Actively maintained, free, Apache-2.0, no EOL risk hanging over it (unlike Camunda 7 CE).
  Flowable 8 tracks Spring Boot 4 / Spring Framework 7, so the project is not stuck on an old baseline.
- Local run is `docker compose up postgres` + `./gradlew bootRun`. Fast test cycle, works in GitHub
  Actions through Testcontainers.

Why not the others (briefly):
- **Camunda 8 / Zeebe** — not embeddable at all: a separate broker plus Elasticsearch plus Operate,
  ~4 GB RAM in docker-compose, process state outside your database, no joins with business data.
  The right choice at production scale, a poor one for fast learning. → moved to Phase 8 as a
  comparison spike.
- **Camunda 7** — conceptually the same as Flowable (they share an ancestor), but the community
  edition is in maintenance mode and gets no new features.
- **Temporal** — powerful, but it is not BPMN: workflow-as-code, no process model, no BPMN
  compensation events. A different mental model and less overlap with the vacancy, which lists
  BPM engines specifically.
- **jBPM** — heavy, tied to the Kie/Drools ecosystem.

**Phase 8:** bring up Camunda 8 Self-Managed via docker-compose and implement *one* process (the
VOIP order) on Zeebe with job workers, so you have a concrete answer to "what is the difference
between an embedded BPM engine and the external-task / job-worker model?"

### Engines live side by side, nothing is ever replaced

Every engine gets its own Gradle module — `engine-flowable`, then `engine-camunda8`, and any
further one after that. A new engine is **never** a rewrite of the previous module: both stay
buildable and runnable at the same time, so the coverage of each engine sits in the working tree
for direct comparison instead of being buried in git history.

That shapes the code from Phase 1 onwards:
- engine-agnostic pieces (TMF DTOs, supplier adapter, idempotency store, outbox, inventory) move
  into a shared `sil-shared` module as soon as the second consumer appears;
- each engine module owns only its process definitions plus the glue that binds them to the shared
  services (delegates for Flowable, job workers for Zeebe);
- the API-level acceptance tests are written once, against the HTTP contract, and both engine
  modules must pass them — that shared suite is the fairest possible comparison;
- `docs/engine-comparison.md` gets a row per feature from the §2 matrix: how each engine does
  timers, retries, compensation, recovery, and what it costs to run.

---

## 1. Domain (mirrors the project from the vacancy)

A Service Integration Layer for a UK broadband/telecom wholesale operator. It orchestrates VOIP and
hardware orders between the client's commercial order management system and downstream suppliers.

```
  Commercial OMS (client)
        │  REST (TMF-like)
        ▼
  ┌─────────────────────────────────┐
  │  SIL (Spring Boot + Flowable)   │
  │  ┌───────────┐  ┌────────────┐  │
  │  │ REST API  │  │ BPMN engine│  │
  │  └───────────┘  └────────────┘  │
  │  outbox → SQS/Rabbit → consumer │
  └─────────────────────────────────┘
        │  REST (OAuth2 client_credentials)
        ▼
  Supplier VOIP API (WireMock stub)   +   Hardware/Shipping API (stub)
```

### Public APIs (simplified TMF Open APIs — useful for the "TM Forum" nice-to-have)
| API | TMF | Endpoints |
|---|---|---|
| Service Qualification | TMF645 | `POST /serviceQualification`, `GET /{id}` |
| Service Ordering | TMF641 | `POST /serviceOrder`, `GET /{id}`, `GET ?state=`, `POST /{id}/cancel` |
| Service Inventory | TMF638 | `GET /service`, `GET /service/{id}` (read-only) |
| Catalog | TMF633 | `GET /serviceSpecification` (config from YAML/DB) |
| Events | TMF | `POST /hub` (listener registration) + webhook callback |

### The six supplier adapter operations (as in the vacancy)
1. `createCustomer`
2. `createSubscription`
3. `createUser`
4. `reserveNumber` (reserve a phone number)
5. `activateNumber` / port-in with an asynchronous callback
6. `shipHardware` (long-running — status takes weeks; polling plus timers)

---

## 2. Matrix: "engine feature → where it lives → how it is proven"

This is the core table. Each row is a branch/PR, and each one has a test.

| # | Feature | Where it is implemented | How the test proves it |
|---|---|---|---|
| 1 | BPMN process definition, deployment, versioning | `serviceOrder.bpmn20.xml`, Flowable auto-deploy | deploy v2 while v1 instances are live; old ones keep running the old model |
| 2 | Service tasks (JavaDelegate vs `${bean.method()}`) | supplier adapter delegates | delegate unit test plus a process test |
| 3 | Persisted state / process variables | PostgreSQL `ACT_RU_*` / `ACT_HI_*` | `SELECT` in the test, JSON variables |
| 4 | Async continuations and transaction boundaries | `flowable:async="true"` on service tasks | business transaction rolls back without rolling back the process |
| 5 | **Retry with backoff** | `flowable:failedJobRetryTimeCycle="R5/PT10S"` | stub returns 500 three times → the fourth attempt succeeds |
| 6 | Incident / dead letter job | job moves to `ACT_RU_DEADLETTER_JOB` | after retries are exhausted: alert plus manual retry via admin API |
| 7 | **Intermediate timer** (wait N minutes before polling) | `<timerEventDefinition>` | Flowable test clock: `processEngineConfiguration.getClock().setCurrentTime(...)` |
| 8 | **Interrupting boundary timer** (order SLA timeout) | boundary event on a call activity | timer fires → escalation path |
| 9 | **Non-interrupting boundary timer** (reminder every 24h) | cycle timer | N firings without killing the token |
| 10 | **Message correlation** (async supplier callback) | `receiveTask` / message catch plus `correlationId` | `POST /callbacks/voip` → the process moves on |
| 11 | **Idempotency** | `Idempotency-Key` header → `idempotency_record` table with a unique constraint | two identical POSTs → one process instance, the same 201 body |
| 12 | **Correlation ID / traceability** | MDC filter, `X-Correlation-Id` through logs, process variables, outbound calls | test asserts the header in the WireMock request journal |
| 13 | **Compensation (saga)** | `compensateEventDefinition` plus handlers: `deleteSubscription`, `releaseNumber` | failure at step 5 → compensation unwinds steps 1–4 in reverse order |
| 14 | Error boundary events, BPMN error vs technical failure | `BpmnError("SUPPLIER_REJECTED")` vs `RuntimeException` | different process branches |
| 15 | **Recovery after restart** ⭐ | nothing to write — the state is in the database | integration test: start a process → kill the app container → bring it up → the process resumes at the same step |
| 16 | Job executor, cluster-safe | two app instances against one database | no job runs twice (counter in WireMock) |
| 17 | **Transactional outbox** | `outbox_event` table in the same transaction plus a poller → RabbitMQ/LocalStack SQS | the event survives a crash right after commit |
| 18 | **Idempotent consumer** | `processed_message` table at the queue inbound | duplicate delivery → a single effect |
| 19 | Timeouts | RestClient/WebClient connect and read timeouts, Resilience4j `TimeLimiter` | stub delays 10s → fails at 2s → retry |
| 20 | Circuit breaker (bonus) | Resilience4j on the supplier adapter | 50% error rate → open → fallback |
| 21 | **DMN** (bonus, Flowable supports it) | decision table: pick supplier/tariff by postcode and speed | DMN unit test |
| 22 | **User task** (manual approval) | task for high-value orders plus the Flowable REST task API | claim → complete → the process continues |
| 23 | Call activity / subprocess | `provisionSubscriber.bpmn` called from the main process | reuse plus its own test |
| 24 | Multi-instance (parallel) | provisioning N users in parallel | 3 users → 3 supplier calls |
| 25 | Signal / terminate end event | cancel order → terminate | all active tokens stopped, compensation executed |
| 26 | Process and variable history | `HistoryService` → `GET /serviceOrder/{id}/timeline` | audit trail exposed in the API |
| 27 | **OpenAPI plus variance log** | springdoc-openapi, `docs/variance-log.md` (where we deviate from TMF) | `/v3/api-docs` stored as a CI artifact |
| 28 | OAuth2 client credentials to the supplier | Spring Security OAuth2 Client plus a WireMock token endpoint | token is cached and refreshed on 401 |
| 29 | Metrics | Micrometer: active instances, dead letters, step duration → `/actuator/prometheus` | assert the metrics exist |
| 30 | Monitoring UI | Flowable UI (docker) **or** a custom `/admin` endpoint | screenshot in the README |

⭐ — item 15 is called out separately in the vacancy ("intentional mid-process restart"), so it is a
must-have demo.

---

## 3. Stack

- Java 25 (Gradle toolchain)
- Spring Boot 4.1.x, Gradle Kotlin DSL
- `flowable-spring-boot-starter-process` 8.x (the line built against Spring Boot 4 / Spring
  Framework 7; plus `flowable-spring-boot-starter-actuator` later)
- PostgreSQL 16, Liquibase for our own tables (Flowable creates its own; in production set that to
  `false` and use generated DDL)
- WireMock (standalone in docker and as a Testcontainer) — supplier stubs
- RabbitMQ **or** LocalStack (SQS + Secrets Manager) — to touch the AWS side of the vacancy
- Testcontainers 2.x (Postgres + WireMock + Rabbit/LocalStack)
- Resilience4j, springdoc-openapi, Micrometer + Prometheus + Grafana (optional)
- React (Vite + TS) — a small UI: order list, process timeline, "retry dead letter" button, user task
  approval. The vacancy asks for ReactJS, and this covers it cheaply.

---

## 4. Phases

### Phase 0 — skeleton (half a day)
- Gradle multi-module: `sil-api`, `sil-core` (a single module is fine too — do not over-engineer)
- `docker-compose.yml`: postgres, wiremock, rabbitmq (or localstack), optionally flowable-ui
- Spring Boot with the Flowable starter comes up, `ACT_*` tables created
- Health-check test using Testcontainers
- **Artifact:** `./gradlew test` green locally

### Phase 1 — synchronous path, no process yet (half a day)
- TMF645 Service Qualification: REST → supplier `checkAvailability` (WireMock) → response
- OpenAPI generated, Idempotency-Key, correlation-ID filter, timeouts, Resilience4j retry
- Covers items: 11, 12, 19, 20, 27, 28
- **Artifact:** Postman collection plus `docs/variance-log.md` (first entry)

### Phase 2 — the first BPMN process (1–2 days)
- `serviceOrder.bpmn20.xml`: create customer → subscription → user → reserve number → complete
- Service tasks via delegate expressions, async continuations
- `POST /serviceOrder` starts the process, `GET /{id}` reads state from the process plus the database
- Tests: `@SpringBootTest` with `FlowableTestHelper`, `assertThat(processInstance).isEnded()`
- Covers: 1, 2, 3, 4, 26
- **Artifact:** diagram in the README (PNG exported from Flowable Modeler / bpmn.io)

### Phase 3 — the unreliable world (2 days)
- Retry cycle, dead letter, admin endpoint for manual retry
- BPMN error vs technical exception, error boundary events
- Timers: delay before polling hardware status; boundary SLA timer; non-interrupting reminder
- Message correlation: `POST /callbacks/voip/{correlationId}` advances the receive task
- Covers: 5, 6, 7, 8, 9, 10, 14
- **Artifact:** a test that drives the engine clock (no `Thread.sleep`)

### Phase 4 — saga / compensation (1–2 days)
- Compensation handlers for every supplier step
- Scenario: failure at `activateNumber` → `releaseNumber` → `deleteUser` → `deleteSubscription` →
  `deleteCustomer`
- Cancel order: terminate plus compensation
- Covers: 13, 25
- **Artifact:** a test asserting the **order** of compensation calls in the WireMock journal

### Phase 5 — durability and recovery (1 day) ⭐ the headline demo
- Integration test: start an order → the process waits on a timer or message →
  `docker restart sil-app` (or stop/start the Spring context against the same database) → the
  process runs to completion
- Two instances against one database: verify no job executes twice
- Transactional outbox plus poller plus idempotent consumer
- Covers: 15, 16, 17, 18
- **Artifact:** `docs/recovery-demo.md` with step-by-step commands and logs — this is what you show
  in an interview

### Phase 6 — inventory, catalog, DMN, user task, UI (1–2 days)
- TMF638 read-only inventory (populated by the process on success)
- Catalog from YAML
- DMN table for supplier selection
- User task approval for orders above £X
- React UI: order list, timeline, approve, retry dead letter
- Covers: 21, 22, 23, 24, 29, 30

### Phase 7 — CI (half a day, in parallel with everything)
GitHub Actions `ci.yml`:
```yaml
jobs:
  build:      # ./gradlew build — unit + BPMN process tests (in-memory H2 for speed)
  it:         # ./gradlew integrationTest — Testcontainers: postgres + wiremock + rabbit
  restart-it: # docker compose up -d; start an order; docker compose restart app;
              # assert the process completed — the recovery test as its own job
  artifacts:  # openapi.json, BPMN diagrams, jacoco report
```
Also: a Java 21/25 matrix, `gradle/actions/setup-gradle` caching, `--fail-fast`.

### Phase 8 — second engine: Camunda 8, added next to the first (one evening)
- `docker-compose -f docker-compose.camunda8.yml up` (Zeebe + Operate + Elasticsearch)
- New module `engine-camunda8` implementing the same `serviceOrder` journey: the BPMN is nearly the
  same, service tasks become job workers (`@JobWorker` from spring-zeebe), variables are JSON, and
  there is no join with the business database
- `engine-flowable` stays untouched and buildable; the shared API acceptance suite runs against both
- `docs/engine-comparison.md`: embedded vs remote engine, transactional coupling, scaling,
  exporters, how compensation and timers work in each, operational cost

---

## 5. Repository layout

```
workflow_orchestration_test/
├── PLAN.md                     ← this file
├── README.md                   ← how to run, diagrams, what it demonstrates
├── CLAUDE.md                   ← working rules (language, commits, engines side by side)
├── docker-compose.yml          ← postgres + wiremock + rabbitmq
├── docker-compose.camunda8.yml ← Zeebe + Operate + Elasticsearch (Phase 8)
├── settings.gradle.kts         ← one module per engine, plus the shared module
├── docs/
│   ├── architecture.md
│   ├── variance-log.md         ← deviations from the TMF spec (a vacancy requirement)
│   ├── recovery-demo.md
│   └── engine-comparison.md    ← feature-by-feature: Flowable vs Camunda 8
├── sil-shared/                 ← engine-agnostic (from Phase 1/2, when the 2nd consumer appears)
│   └── src/main/java/.../
│       ├── api/                (TMF DTOs, idempotency filter, correlation filter)
│       ├── supplier/           (adapter, OAuth2 client, resilience config)
│       ├── inventory/          (read-only inventory and catalog)
│       └── messaging/          (outbox, poller, consumer)
├── engine-flowable/
│   └── src/
│       ├── main/resources/processes/
│       │   ├── serviceOrder.bpmn20.xml
│       │   ├── provisionSubscriber.bpmn20.xml
│       │   ├── hardwareShipment.bpmn20.xml
│       │   └── supplierSelection.dmn
│       ├── main/java/.../workflow/   (delegates, listeners, compensation handlers, admin/job API)
│       └── test/java/...             (unit, process and Testcontainers tests)
├── engine-camunda8/            ← same journey, Zeebe job workers (Phase 8, added not swapped)
├── stubs/                      ← WireMock mappings plus failure scenarios
├── ui/                         ← React + Vite
└── .github/workflows/ci.yml
```

---

## 6. Learning order (so you do not drown)

1. Read the BPMN minimum: start/end events, service task, gateway, boundary event, timer, message,
   compensation, call activity, multi-instance. About two hours on bpmn.io plus the Flowable docs.
2. Draw the BPMN diagrams in bpmn.io (free, works with Flowable XML) rather than hand-editing XML
   in the IDE.
3. For every feature in §2, write the **test first**, then the implementation. Flowable process
   tests are fast on H2, so the cycle stays short.
4. After each phase, write three to five sentences in the README: "what the engine does here, and
   what I did not know before." Those are ready-made interview answers.

## 7. "Interview-ready" checklist
- [ ] I can draw the process on a whiteboard and explain the token model
- [ ] I can explain async continuation vs transaction boundary
- [ ] I can explain why retries belong in the engine rather than in a `for` loop
- [ ] I can show compensation running in reverse order in the logs
- [ ] I can demo recovery after `docker restart` live
- [ ] I can explain idempotency at three levels: HTTP, job executor, queue consumer
- [ ] I can explain embedded (Flowable) vs remote (Zeebe) engines and when to pick which
- [ ] OpenAPI and the variance log are shown as CI artifacts
