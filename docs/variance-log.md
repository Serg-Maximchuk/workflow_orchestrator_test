# Variance log

Where this implementation deliberately differs from the TM Forum Open API specifications, and why.

The point of keeping this is not bureaucracy. On an integration project the gap between "the spec
we agreed" and "the contract we shipped" is where the expensive surprises live, and a variance is
only safe once it is written down, owned and agreed. Anything not listed here is meant to follow
the specification.

Status values: **agreed** (accepted by the architect), **proposed** (raised, awaiting a decision),
**temporary** (will be closed before the phase ends).

---

## TMF645 Service Qualification

| # | Variance | Reason | Status |
|---|---|---|---|
| V-001 | Resource path is `/tmf-api/serviceQualification/v5/checkServiceQualification` rather than the full `/serviceQualificationManagement/v5/...` | Shorter path for a learning project; no functional difference | agreed |
| V-002 | `serviceQualificationItem` array collapsed into a single flat request: one `place`, one `serviceSpecId`, one `requestedSpeedMbps` | Phase 0 covers exactly one service at one address. The array shape is added when multi-item qualification is actually needed, rather than shipping an unused array | agreed |
| V-003 | `place` reduced to `postcode` plus optional `streetAddress`; the full TMF `RelatedPlaceRefOrValue` is not implemented | The supplier's availability API keys off postcode alone, so the remaining fields would be carried but never used | agreed |
| V-004 | Response omits `expirationDate`, `qualificationDate`, `@type` and `@baseType` | Not consumed by anything downstream yet; adding them without a consumer would freeze a shape we have not validated | proposed |
| V-005 | `state` is always `done`; the TMF task lifecycle (`acknowledged`, `inProgress`, `terminatedWithError`) is not modelled | Qualification is synchronous in Phase 1. When it moves behind a workflow the real lifecycle arrives with it | temporary — revisit in Phase 2 |
| V-006 | `correlationId` added to the response, which is not a TMF field | Makes a single order journey traceable from the caller's side without reading our logs. Additive, so it does not break a TMF-conformant client | agreed |

## TMF641 Service Ordering

| # | Variance | Reason | Status |
|---|---|---|---|
| V-012 | Path is `/tmf-api/serviceOrdering/v4/serviceOrder` rather than `/serviceOrderingManagement/v4/...` | Consistent with V-001 | agreed |
| V-013 | `serviceOrderItem` array collapsed into one flat order: one customer, one place, one service spec | One service per order in Phase 2. The array arrives with multi-item orders, not before | proposed |
| V-014 | `supplierRefs` object added, which is not a TMF field | Makes the fulfilment progress visible without exposing engine internals. A null field means that step has not completed | agreed |
| V-015 | `GET /{id}/timeline` is not a TMF endpoint | Reads the engine's own history. Genuinely useful for support, and deliberately kept outside the TMF surface so it can change freely | agreed |
| V-016 | Order states limited to `acknowledged`, `inProgress`, `completed`, `failed`; TMF also defines `pending`, `held`, `assessingCancellation`, `pendingCancellation`, `cancelled`, `rejected`, `partial` | Only the states the current process can actually reach. Cancellation states arrive in Phase 4 with the cancel journey | temporary — revisit in Phase 4 |

| V-017 | `POST /callbacks/voip/number-activation` is our own shape, not a TMF notification | The supplier is not TMF-aware. Correlation is on our order id, which we hand over as `callbackCorrelationId` when requesting activation | temporary — closes when the real supplier callback contract arrives |
| V-019 | `POST /serviceOrder/{id}/cancel` is refused with 409 while a provisioning call is in flight; TMF models cancellation as a separate `CancelServiceOrder` resource with its own lifecycle | Cancellation is caught at the points where the order waits on the supplier, because those are the only points from which the saga can unwind safely. A full `CancelServiceOrder` resource is worth adding once there is a consumer for its states | proposed |
| V-020 | Compensation clears the supplier references it undid rather than keeping them | An id that no longer exists at the supplier invites a later step, or an operator, to act on something that is gone. The history is not lost - the timeline still shows both the step and its compensation | agreed |
| V-018 | `/admin/workflow/**` is outside TMF entirely | Support tooling, not a client-facing API. Kept separate so it can change without touching the TMF contract | agreed |

## Cross-cutting

| # | Variance | Reason | Status |
|---|---|---|---|
| V-007 | `Idempotency-Key` request header is not part of TMF | TMF has no idempotency story for POST, and the client's order management system will retry on timeout. Without it, a network blip creates duplicate resources | agreed |
| V-008 | Errors use RFC 7807 `application/problem+json` rather than the TMF `Error` schema | Native Spring support, and strictly richer than the TMF shape | proposed |
| V-009 | Inbound API is unauthenticated | Deliberate scope cut: the OAuth2 work in this project is on the outbound side, where the supplier requires client credentials. Inbound auth is assumed to be terminated by the API gateway | agreed |

## Supplier contract

| # | Variance | Reason | Status |
|---|---|---|---|
| V-010 | The supplier API is modelled from assumption, not from a published specification: `POST /supplier/v1/availability` with a postcode, answering `available` / `maxSpeedMbps` | No sandbox access. The adapter is deliberately thin so the real contract can be dropped in without touching the qualification logic | temporary — closes when the real supplier spec arrives |
| V-011 | Token endpoint assumed to be OAuth2 client credentials at `/supplier/oauth/token` | Same as V-010 | temporary |
