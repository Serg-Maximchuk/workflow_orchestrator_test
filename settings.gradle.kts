rootProject.name = "service-integration-layer"

// Engine-agnostic contracts, supplier adapter, idempotency and outbox live here so that every
// engine module serves the same HTTP contract instead of reimplementing it.
include("sil-shared")

// One module per workflow engine. Engines are added side by side and none is ever replaced:
// the point of the project is to end up with the same telecom order journey implemented on
// several engines, runnable and comparable at the same time.
include("engine-flowable")

// Added in later phases:
//   include("engine-camunda8") // same journey on Zeebe job workers
