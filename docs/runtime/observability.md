# OpenTelemetry tracing

Ekbatan instruments its action execution pipeline with the **OpenTelemetry API** (`opentelemetry-api`). The library depends only on the API — no SDK. When no OTel SDK is registered at runtime, all tracing calls are no-ops with zero overhead. Consumers bring their own `opentelemetry-sdk` and exporters.

The instrumentation scope is `io.ekbatan.core` version `1.0.0`, obtained from `GlobalOpenTelemetry.get().getTracer(...)`.

## Span hierarchy

Each `executor.execute(...)` call produces a span tree of the following shape:

```
[ekbatan.action.execute]                    ActionExecutor.execute()
├── [ekbatan.action.perform]                Action.perform()
└── [ekbatan.action.persist]                ActionExecutor.persistChanges()
    └── [ekbatan.transaction]               TransactionManager.inTransactionChecked() (per shard)
        ├── [ekbatan.repository]            AbstractRepository.addAllNoResult / updateAllNoResult
        └── [ekbatan.event.persist]         EventPersister.persistActionEvents()
```

A single action that touches one shard produces one of each span. A cross-shard action produces one transaction + nested repo + event-persist spans **per involved shard**.

## Attributes

| Attribute | Type | Span | Description |
|---|---|---|---|
| `ekbatan.action.name` | string | action.execute | Simple class name of the action |
| `ekbatan.action.principal` | string | action.execute | Principal name |
| `ekbatan.action.outcome` | string | action.execute | `"success"` or `"error"` |
| `ekbatan.action.retry.count` | long | action.execute | Total retries (0 if none) |
| `ekbatan.shard.cross_shard` | boolean | action.persist | Present (and `true`) when changes span multiple shards |
| `ekbatan.shard.group` | long | transaction | Shard group identifier |
| `ekbatan.shard.member` | long | transaction | Shard member identifier |
| `db.operation.name` | string | repository | `"INSERT"` or `"UPDATE"`. Follows OTel semantic conventions. |
| `ekbatan.entity.type` | string | repository | Simple class name of the domain object |
| `ekbatan.batch.size` | long | repository | Number of records in the batch |
| `ekbatan.event.count` | long | event.persist | Number of model events persisted |

`db.operation.name` follows OTel semantic conventions. All others use the `ekbatan.*` namespace.

## Retry events

Each retry attempt adds a span event named `"retry"` to the action span with attributes `retry.count` (int) and `retry.exception` (the matched retryable exception class name, which may come from the cause chain). Retries are **not** their own spans — each retry re-executes `perform` + `persist`, so the child spans of each attempt already appear under the action span; a separate retry span would just add a redundant level.

## Errors

On failure, spans are marked with `StatusCode.ERROR` and the exception is recorded via `span.recordException()`.

## Context propagation

Since actions execute single-threaded (ScopedValue-based transactions), context flows naturally via `Span.makeCurrent()` / `Scope`. No async context passing is needed. Each `TransactionManager` instance knows its own `ShardIdentifier` (set at construction time, defaults to `ShardIdentifier.DEFAULT`), so `inTransactionChecked()` automatically sets shard attributes on the transaction span without requiring the shard to be passed per-call.

## Bringing your own SDK

The framework only declares the `opentelemetry-api` dependency. To actually export traces, your application brings the SDK and exporters:

```kotlin
implementation("io.opentelemetry:opentelemetry-sdk")
implementation("io.opentelemetry:opentelemetry-exporter-otlp")
implementation("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")
```

Then configure via standard OTel system properties or environment variables — `otel.exporter.otlp.endpoint`, `otel.service.name`, etc. The framework picks up whatever `GlobalOpenTelemetry` returns.

In tests, register an in-memory exporter via `opentelemetry-sdk-testing` to assert on emitted spans:

```kotlin
testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
```

The `:ekbatan-core:tracingTest` Gradle task forks a separate JVM specifically because the OTel SDK must be registered before any instrumented class loads its static `Tracer` field via `GlobalOpenTelemetry`.

## Read-path tracing is not instrumented

`AbstractRepository`'s read methods (`findById`, `findAllWhere`, `count`, etc.) don't produce their own spans. They run inside `Action.perform()` which already has a span, and instrumenting every read would be noisy for actions that do many lookups. If you need fine-grained read tracing, instrument those calls in your repository subclass.

## See also

- [Actions, ActionPlan, ActionExecutor](../concepts/actions.md) — the source of `action.execute` / `action.perform` / `action.persist`
- [Sharding](../database/sharding.md) — `ekbatan.shard.*` attributes come from the per-shard `TransactionManager`
- [Repositories on JOOQ](../database/repositories.md) — what the `ekbatan.repository` span wraps
