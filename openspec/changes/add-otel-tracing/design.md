## Context

Ekbatan is a Java persistence and action framework (library). Its execution pipeline flows through well-defined layers: ActionExecutor -> Retry -> Action.perform -> persistChanges -> TransactionManager -> Repository/EventPersister. Today, consumers have no built-in visibility into this pipeline. They must manually instrument or guess where time is spent.

The framework uses `ScopedValue<Transaction>` for thread-safe transaction context and enforces single-threaded action execution. This means OTel context propagation via `makeCurrent()`/`Scope` works naturally — no async context passing needed.

Key files to instrument:
- `ActionExecutor.java` — orchestrates the full action lifecycle
- `Retry.java` — retry loop (package-private)
- `TransactionManager.java` — manages database transactions per shard
- `AbstractRepository.java` — all CRUD operations
- `ChangePersister.java` — persists domain changes and delegates to EventPersister

## Goals / Non-Goals

**Goals:**
- Provide distributed tracing instrumentation across the action execution pipeline
- Use only `opentelemetry-api` — consumers bring their own SDK
- Zero overhead when no OTel SDK is present (all calls resolve to no-ops)
- Spans form a meaningful parent-child hierarchy that reflects Ekbatan's execution model
- Attributes follow OTel semantic conventions where applicable, with `ekbatan.*` namespace for framework-specific attributes

**Non-Goals:**
- Metrics instrumentation (future work)
- Logging changes (OTel SDK auto-populates MDC — no Ekbatan changes needed)
- Tracing inside domain objects (Model, Entity, Action.perform internals)
- Providing a configurable Tracer — the library uses `GlobalOpenTelemetry.getTracer()` and the consumer configures the SDK globally
- Making tracing optional via a feature flag or separate module — `opentelemetry-api` is lightweight enough to always include

## Decisions

### 1. Single `opentelemetry-api` dependency in `ekbatan-core`

The OTel API jar is ~100KB with no transitive dependencies. A separate `ekbatan-otel` module would add wiring complexity (decorators/wrappers around every instrumented class) for negligible dependency savings. Since the API is a no-op without the SDK, there is no cost to including it unconditionally.

**Alternative considered:** Separate `ekbatan-otel` module with decorator pattern. Rejected because it would require wrapping ActionExecutor, TransactionManager, AbstractRepository, ChangePersister — too much indirection for a dependency that is already designed to be zero-cost when unused.

### 2. Tracer obtained from `GlobalOpenTelemetry`

Each instrumented class obtains its `Tracer` via `GlobalOpenTelemetry.get().getTracer("io.ekbatan.core")`. This follows the standard library instrumentation pattern — the application configures `GlobalOpenTelemetry` once at startup, and all libraries pick it up.

**Alternative considered:** Accept a `Tracer` via constructor/builder injection. Rejected because it would change public API surfaces (ActionExecutor.Builder, TransactionManager constructor) and push OTel configuration responsibility onto the consumer for every Ekbatan instance. The global approach is idiomatic for libraries.

### 3. Span hierarchy follows execution flow

```
[ekbatan.action.execute]                    ActionExecutor.execute()
├── [ekbatan.action.perform]                Action.perform()
└── [ekbatan.action.persist]                ActionExecutor.persistChanges()
    └── [ekbatan.transaction]               TransactionManager.inTransactionChecked() (per shard)
        ├── [ekbatan.repository.<op>]       AbstractRepository add/update operations
        └── [ekbatan.event.persist]         EventPersister.persistActionEvents()
```

Retry attempts are recorded as span events on the `ekbatan.action.execute` span, not as separate spans. Rationale: each retry re-executes the full action (including perform + persist), so the child spans of each attempt are already visible. A separate retry span would add a redundant nesting level.

### 4. Retry instrumentation approach

The current `Retry.execute()` method is a tight loop inside `ActionExecutor.execute()`. The action span wraps the retry loop. On each retry:
- A span event `"retry"` is added to the action span with attributes: `retry.attempt` (int), `retry.exception` (string)
- The action span attribute `ekbatan.action.retry.count` is updated with the total retry count after execution completes

This requires Retry to have access to the current span. Since Retry is package-private and only called from ActionExecutor, the span can be passed as a parameter or read from `Span.current()`.

### 5. Repository span granularity

Repository spans are created only for write operations invoked during action persistence (`addAllNoResult`, `updateAllNoResult`). Read operations (`findById`, `findAll`, etc.) are not instrumented in this change because:
- They happen inside `Action.perform()`, which already has its own span
- The interesting performance data is in the write path (which is transactional)
- Adding spans to every read would be noisy for actions that do many lookups

Read operation tracing can be added in a future iteration if needed.

### 6. Attribute naming

| Attribute | Type | Where | Example |
|---|---|---|---|
| `ekbatan.action.name` | string | action span | `"WalletDepositAction"` |
| `ekbatan.action.principal` | string | action span | `"user-42"` |
| `ekbatan.action.outcome` | string | action span | `"success"` or `"error"` |
| `ekbatan.action.retry.count` | long | action span | `2` |
| `ekbatan.shard.group` | long | transaction span | `1` |
| `ekbatan.shard.member` | long | transaction span | `0` |
| `ekbatan.shard.cross_shard` | boolean | persist span | `true` |
| `db.operation.name` | string | repository span | `"INSERT"`, `"UPDATE"` |
| `ekbatan.entity.type` | string | repository span | `"Wallet"` |
| `ekbatan.batch.size` | long | repository span | `3` |
| `ekbatan.event.count` | long | event persist span | `2` |

`db.operation.name` follows OTel semantic conventions. All others use the `ekbatan.*` namespace.

## Risks / Trade-offs

**[Risk] OTel API version compatibility** — The OTel API follows semver and has been stable (1.x) since 2021. Pinning to a recent stable version (1.47+) and using only core tracing APIs (Tracer, Span, Scope) minimizes breakage risk.
→ Mitigation: Depend on `opentelemetry-api` with a version range or BOM that consumers can override.

**[Risk] Span overhead in hot paths** — AbstractRepository methods may be called many times per action. Even no-op spans have a method call cost.
→ Mitigation: Only instrument batch write operations (addAllNoResult, updateAllNoResult) called during persistence, not individual reads. The overhead is one span per entity type per shard per action — bounded and small.

**[Risk] Breaking existing tests** — Adding span creation to core classes could affect test behavior if tests mock or assert on method call counts.
→ Mitigation: Spans are created internally using GlobalOpenTelemetry which returns no-ops by default. Existing tests won't see any behavioral change. New tracing-specific tests use `opentelemetry-sdk-testing` with in-memory exporter.
