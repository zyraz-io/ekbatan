## 1. Dependencies

- [x] 1.1 Add `opentelemetry-api` (1.60.1) as a compile dependency to `ekbatan-core/build.gradle.kts`
- [x] 1.2 Add `opentelemetry-sdk-testing` as a testImplementation dependency to `ekbatan-core/build.gradle.kts`
- [x] 1.3 Add OTel version property to `gradle.properties` for centralized version management

## 2. Action Execution Tracing

- [x] 2.1 Instrument `ActionExecutor.execute()` — create `ekbatan.action.execute` span wrapping the full execution lifecycle, with attributes `ekbatan.action.name`, `ekbatan.action.principal`, `ekbatan.action.outcome`, and status OK/ERROR
- [x] 2.2 Instrument `ActionExecutor.execute()` — create `ekbatan.action.perform` child span wrapping the `action.perform()` call
- [x] 2.3 Instrument `ActionExecutor.persistChanges()` — create `ekbatan.action.persist` child span wrapping persistence, with `ekbatan.shard.cross_shard` attribute when applicable

## 3. Retry Tracing

- [x] 3.1 Modify `Retry.execute()` to record retry span events on the current span — event name `"retry"` with `retry.attempt` and `retry.exception` attributes
- [x] 3.2 Record `ekbatan.action.retry.count` attribute on the action span after execution completes (0 when no retries)

## 4. Persistence Layer Tracing

- [x] 4.1 Instrument `TransactionManager.inTransactionChecked()` — create `ekbatan.transaction` span with `ekbatan.shard.group` and `ekbatan.shard.member` attributes, status OK on commit, ERROR on rollback. Added `ShardIdentifier` parameter overload.
- [x] 4.2 Instrument `AbstractRepository.addAllNoResult()` — create `ekbatan.repository` span with `db.operation.name` = `"INSERT"`, `ekbatan.entity.type`, and `ekbatan.batch.size` attributes
- [x] 4.3 Instrument `AbstractRepository.updateAllNoResult()` — create `ekbatan.repository` span with `db.operation.name` = `"UPDATE"`, `ekbatan.entity.type`, and `ekbatan.batch.size` attributes

## 5. Event Persistence Tracing

- [x] 5.1 Instrument `DualTableEventPersister.persistActionEvents()` — create `ekbatan.event.persist` span with `ekbatan.action.name` and `ekbatan.event.count` attributes
- [x] 5.2 Instrument `SingleTableEventPersister.persistActionEvents()` — create `ekbatan.event.persist` span with `ekbatan.action.name` and `ekbatan.event.count` attributes

## 6. Tests

- [x] 6.1 Write unit tests for `ActionExecutor` tracing — verify span names, attributes, parent-child relationships, and error recording using in-memory span exporter
- [x] 6.2 Write unit tests for retry tracing — verify retry events and retry count attribute on action span
- [ ] 6.3 Write unit tests for `TransactionManager` tracing — verify transaction span with shard attributes and status
- [ ] 6.4 Write unit tests for `AbstractRepository` tracing — verify repository spans for addAllNoResult and updateAllNoResult
- [ ] 6.5 Write unit tests for `EventPersister` tracing — verify event persist spans for both dual-table and single-table implementations
- [ ] 6.6 Write integration test verifying full span hierarchy (action -> perform + persist -> transaction -> repository + event persist) end-to-end

## 7. Documentation

- [x] 7.1 Update AGENTS.md with tracing architecture section — instrumentation scope, span hierarchy, attribute naming conventions
