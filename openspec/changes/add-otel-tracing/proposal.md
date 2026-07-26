## Why

Ekbatan provides a structured action execution pipeline (ActionExecutor -> Action.perform -> Transaction -> Repository -> EventPersister), but there is no built-in observability into this pipeline. When something is slow or fails in production, the application developer has no visibility into which phase took how long, which shard was hit, or how many retries occurred. Adding OpenTelemetry tracing instrumentation to the framework — using the API only, not the SDK — gives every Ekbatan consumer distributed tracing for free when they bring their own OTel SDK, with zero overhead when they don't.

## What Changes

- Add `opentelemetry-api` as a compile dependency to `ekbatan-core`
- Instrument `ActionExecutor.execute()` with a root span per action execution (action name, principal, shard, outcome)
- Instrument `Action.perform()` with a child span for the business logic phase
- Instrument `ActionExecutor.persistChanges()` with a child span for the persistence phase
- Instrument `TransactionManager.inTransaction()` / `inTransactionChecked()` with a child span per database transaction (shard identifier)
- Instrument `AbstractRepository` CRUD operations with child spans (entity type, operation, batch size)
- Instrument `ChangePersister.persist()` and `EventPersister.persistActionEvents()` with child spans (event count)
- Record retry attempts as span events/attributes on the action span in `Retry`
- Add `opentelemetry-sdk-testing` as a test dependency for verifying span creation with in-memory exporter

## Capabilities

### New Capabilities
- `action-tracing`: Tracing instrumentation for the action execution pipeline (ActionExecutor, Action.perform, Retry)
- `persistence-tracing`: Tracing instrumentation for the persistence layer (TransactionManager, AbstractRepository, ChangePersister, EventPersister)

### Modified Capabilities

## Impact

- **Dependencies**: `opentelemetry-api` added to `ekbatan-core` compile scope (~100KB, no transitive dependencies). `opentelemetry-sdk-testing` added to test scope.
- **Affected code**: `ActionExecutor`, `Retry`, `TransactionManager`, `AbstractRepository`, `ChangePersister` — each gains span creation/management code in their core methods.
- **API surface**: No breaking changes. Existing public interfaces unchanged. The `Tracer` is obtained internally from `GlobalOpenTelemetry` — consumers do not need to pass or configure anything in Ekbatan.
- **Performance**: Zero overhead when no OTel SDK is present (all API calls are no-ops). Negligible overhead when SDK is present (span creation is lightweight).
