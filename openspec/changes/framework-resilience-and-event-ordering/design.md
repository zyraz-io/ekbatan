## Context

Ekbatan is preparing for its v1.0.0 release. During evaluation, three core runtime & event delivery behaviors were identified for refinement:
1. **Cross-Shard Failure Visibility**: When `allowCrossShard = true` actions fail on a middle shard, earlier shards remain committed. Operators need explicit log entries stating exactly which shards committed before the failure occurred.
2. **Advisory Lock Acquire Semantics**: the SQL-backed `KeyedLockProvider` implementations declare `throws InterruptedException` but cannot deliver it - `acquire` parks in a JDBC socket read no interrupt can break. Backend failures are also untyped, and connection cleanup can mask the failure that triggered it.
3. **Per-Entity Event Dispatch Ordering**: `EventHandlingJob` in `local-event-handler` dispatches due notifications in parallel across virtual threads. When multiple events arrive for the same aggregate (`model_id`), some domain models require sequential, ordered execution per entity rather than race-prone parallel dispatch.

## Goals / Non-Goals

**Goals:**
- Add structured warning logging in `ActionExecutor.persistChanges()` detailing committed vs failed shards during multi-shard action execution failures.
- Make blocking `acquire` interruptible on the SQL-backed `KeyedLockProvider` implementations, introduce a typed `LockAcquisitionException`, and stop cleanup failures from masking the original error.
- ~~Provide a `sequentialPerEntity` configuration option in `local-event-handler`.~~ **Deferred** - see `specs/in-order-local-event-dispatch/spec.md`.

**Non-Goals:**
- Automatic 2PC or Saga rollback framework inside `ActionExecutor` (users implement sagas explicitly using `DistributedJob` when needed).
- Automated retention cleanup jobs for `eventlog.event_notifications` (deferred to application-level jobs for now).
- Built-in cursor pagination utilities in `AbstractRepository`.

## Decisions

### Decision 1: Structured Warning Log for Multi-Shard Action Partial Commit
- **Rationale**: When `ActionExecutor.persistChanges()` loops over `changesByShard`, track `committedShards` set. If an exception is thrown while processing shard $N$, log an explicit ERROR:
  `"Action {} failed on shard {}. Previously committed shards: {}. Manual compensation or investigation may be required."`
- **Alternatives Considered**:
  - *Automated Saga Compensation*: Rejected to keep framework simple and avoid imposing a fixed rollback model on users who handle cross-shard workflows via explicit sagas.

### Decision 2: Segmented, interruptible `acquire` on the SQL-backed providers

**Superseded.** The original decision here was connection liveness checks (`isClosed()`,
`isValid(...)`) plus release-path hardening. Review found the release path already does exactly
what was proposed - `lockRelease` / `backendRelease` catch `SQLException`, log, and evict, and
`HeldLease.close()` clears holder state and interrupts the watchdog *before* the backend callback -
and that it is covered by existing passing tests. A pre-flight liveness check also cannot prevent
the hang it targeted, since the connection is alive when checked and dies during the wait.

- **Rationale**: implement `acquire(key, maxHold)` as a loop over the existing bounded `tryAcquire`
  path using a 5-second segment, checking `Thread.interrupted()` between segments. This makes the
  declared `InterruptedException` real, and keeps the connection sending traffic often enough that
  idle-connection reapers stop dropping long lock waits.
- **Alternatives Considered**:
  - *Framework-set socket read timeout* (`Connection.setNetworkTimeout`): rejected - imposing
    socket policy on the user's pooled connections is the operator's call, not the library's.
  - *Documentation only* ("acquire is not interruptible; set `socketTimeout`"): rejected - leaves
    an interface that lies about its own contract.
  - *Cancel-based interruption* (`Statement.cancel()` from a watcher thread): preserves FIFO and
    gives immediate response, but carries a race where a cancel landing just after the grant leaves
    an untracked held lock. Not worth the complexity until starvation is actually observed.
- **Trade-off accepted**: each segment re-enters the database's wait queue at the back, so fairness
  degrades under sustained contention. FIFO was never promised for these backends.

### Decision 3: Per-Entity Sequential Dispatch in `EventHandlingJob` - DEFERRED, NOT IMPLEMENTED
- **Rationale**: Introduce `LocalEventHandlerConfig.sequentialPerEntity` (default `false`). When `true`, `EventHandlingJob` groups the polled batch of due notifications by `model_id`. Notifications with the same `model_id` are processed sequentially in order of `event_date` / `next_retry_at`, while different `model_id` groups are submitted concurrently to virtual threads.
- **Alternatives Considered**:
  - *Single-threaded dispatch*: Degrades throughput globally across all entities.
  - *Kafka-style partition routing*: Overkill for in-process DB polling. Grouping by `model_id` within the batch provides entity ordering with high concurrency across entities.

## Risks / Trade-offs

- **[Risk] High-volume `model_id` skew in sequential dispatch mode**: A single `model_id` with thousands of queued events could bottleneck processing for that specific entity.
  - *Mitigation*: Batch limits and virtual thread execution keep other `model_id` groups moving independently.
- **[Risk] Log verbosity on partial cross-shard failures**: Repeated failures could produce log spikes.
  - *Mitigation*: Log only on failure boundary per action execution attempt.

## Open Questions

- Should `sequentialPerEntity` be configurable per-`EventHandler` or globally on `LocalEventHandlerConfig`? (Default proposal: global setting on `LocalEventHandlerConfig`, with optional override on `@EkbatanEventHandler`).
