# Service Integration Layer — workflow engine playground

A learning project built around workflow orchestration: a telecom **Service Integration Layer**
that drives VOIP and hardware order journeys between a commercial order management system and
downstream suppliers, implemented on top of a BPMN engine.

The point is not the telecom domain — it is to hit, with tests, every orchestration concern that
shows up in real integration work: long-running processes, timers, retries, dead letters,
compensation (saga), idempotency, correlation IDs, transactional outbox, and clean recovery after a
mid-process restart.

The full breakdown lives in [PLAN.md](PLAN.md).

## Engines

Each engine gets its own module and **none replaces another** — the same order journey is meant to
end up implemented several times, side by side, so the implementations can be compared directly.

| Module | Engine | Model | Status |
|---|---|---|---|
| [`engine-flowable`](engine-flowable) | Flowable 8, embedded | BPMN 2.0, engine inside the Spring context, state in the app's own Postgres | Phases 0-1 done |
| `engine-camunda8` | Camunda 8 / Zeebe | BPMN 2.0, remote broker, job workers | planned |

Engine-agnostic code - the TMF APIs, the supplier adapter, idempotency and correlation - lives in
[`sil-shared`](sil-shared), so adding an engine means adding process definitions and their glue,
not a second copy of the API.

## What works today

TMF645 Service Qualification, end to end and synchronous:

- `POST /tmf-api/serviceQualification/v5/checkServiceQualification` calls the supplier and stores
  the answer; `GET .../{id}` returns it later
- retrying with the same `Idempotency-Key` replays the first answer and does **not** call the
  supplier again; reusing the key with a different body is a 409
- `X-Correlation-Id` is echoed to the caller, written to every log line, forwarded to the supplier
  and stored with the result
- the supplier call has connect and read timeouts, bounded retry with exponential backoff, and a
  circuit breaker; a supplier outage becomes a 503 rather than a hung thread
- outbound calls carry an OAuth2 client-credentials token, fetched once and reused
- OpenAPI at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`, deviations from TMF recorded in
  [docs/variance-log.md](docs/variance-log.md)

Not yet: any workflow. That starts in Phase 2, and the retrying and state ownership move into the
engine with it.

## Requirements

- JDK 25 (the Gradle toolchain pins the build to 25 and will provision it if missing)
- Docker (integration tests and the local stack)

## Running

Start the local stack — Postgres, WireMock (supplier stubs), RabbitMQ:

```bash
docker compose up -d
```

Run the Flowable application:

```bash
./gradlew :engine-flowable:bootRun
```

Health check:

```bash
curl -s localhost:8080/actuator/health
```

## Tests

Two lanes, mirroring the two CI jobs.

Fast — unit and BPMN process tests on in-memory H2, no Docker needed:

```bash
./gradlew test
```

Integration — the same engine against a real Postgres via Testcontainers:

```bash
./gradlew integrationTest
```

Everything at once:

```bash
./gradlew build
```

## Layout

```
.
├── PLAN.md                 # phase-by-phase plan and the feature/test matrix
├── CLAUDE.md               # working rules for this repo
├── docker-compose.yml      # postgres + wiremock + rabbitmq
├── sil-shared/             # TMF APIs, supplier adapter, idempotency, correlation
├── engine-flowable/        # Service Integration Layer on an embedded Flowable engine
├── stubs/                  # WireMock mappings standing in for supplier APIs
├── postman/                # collection for poking at the API by hand
├── docs/                   # architecture, variance log, recovery demo, engine comparison
└── .github/workflows/ci.yml
```

## Notes

- Spring Boot 4.1.0 with Flowable 8.0.0. Flowable 8 is the line built against Spring Boot 4.0.x /
  Spring Framework 7; the older Flowable 7.x line would pin the whole build back to Spring Boot 3.x.
- Flowable manages its own `ACT_*` schema in the same database as the business data. That
  co-location — process state and business state in one transaction — is the main reason this
  project starts with an embedded engine rather than a remote broker.
