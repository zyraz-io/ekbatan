## Why

As Ekbatan approaches its 1.0.0 release, core runtime resilience, observability during edge-case failures, and event delivery guarantees need to be strengthened:
1. When multi-shard actions fail partially during cross-shard persistence (`allowCrossShard = true`), operators currently lack prominent, explicit log warnings identifying which shards succeeded and which failed.
2. The SQL-backed `KeyedLockProvider` implementations do not honor the interruptible blocking contract their interface declares — `acquire` parks in a JDBC socket read that `Thread.interrupt()` cannot break, so a blocked caller has no way out. Backend failures also surface as untyped `RuntimeException`, and a failure while returning the borrowed connection can mask the error that caused it. *(Originally framed as "connection liveness verification and `LockLostException`"; see the capability spec for why that framing was discarded.)*
3. Applications that rely on `local-event-handler` for in-process business domain events sometimes require strict in-order delivery per entity (`model_id`) rather than concurrent virtual thread dispatch for the same aggregate instance.

## What Changes

- Add explicit, structured warning logging in `ActionExecutor.persistChanges()` when a cross-shard transaction loop encounters a failure after one or more prior shards have already committed.
- Make the SQL-backed `KeyedLockProvider` implementations (`PostgresKeyedLockProvider`, `MySQLKeyedLockProvider`, `MariaDBKeyedLockProvider`) honor the interruptible blocking contract their interface declares, raise a typed `LockAcquisitionException` on backend failure, and stop connection-cleanup failures from masking the original error.
  - *Superseded during review:* the original plan was pre-flight connection liveness checks plus release-path hardening across all four providers including Redis. Both turned out to target already-working, already-tested behavior, and neither addressed the actual hang. See the capability spec's "Explicitly out of scope" section.
- ~~Introduce an optional per-entity sequential dispatch mode to `EventHandlingJob` and `LocalEventHandlerConfig`.~~ **Deferred — see below.**

## Capabilities

### New Capabilities
- `cross-shard-failure-logging`: Prominent structured warning logging when multi-shard action persistence experiences partial shard commits.
- `keyed-lock-acquire-semantics`: **Rescoped during review.** Pre-flight connection liveness checks turned out to be redundant (the release path already degrades gracefully, covered by existing tests) and could not fix the hang they targeted. Now covers the defects that are actually present: an interruptible blocking `acquire`, a typed `LockAcquisitionException`, and cleanup that never masks the original failure. See the capability's spec for the full reasoning.

### Deferred
- `in-order-local-event-dispatch`: **Parked, not delivered.** The problem is real — `EventHandlingJob` does dispatch same-entity events concurrently — but the framework already answers it twice (guard-based idempotent handlers in-process; `model_id` partition keys on the CDC path), ordering-plus-retries-plus-bounded-latency is a trilemma, and no concrete driver exists. The spec file records the analysis and the four design questions that must be settled before any revival.

### Modified Capabilities
<!-- None -->

## Impact

- `ekbatan-core`: `ActionExecutor.persistChanges()` partial-commit logging and span attributes; new `io.ekbatan.core.concurrent.LockAcquisitionException`; `acquire` / `tryAcquire` rewritten in `PostgresKeyedLockProvider`, `MySQLKeyedLockProvider`, `MariaDBKeyedLockProvider`.
- `ekbatan-events:local-event-handler`: **untouched** — the capability that would have changed it is deferred.
- Docs: `docs/database/keyed-locks.md` and `website/src/pages/reference/keyed-locks.mdx`.

### Backward compatibility — NOT fully compatible

Two deliberate breaking changes, both accepted on the basis that the framework has no serious
production users yet:

1. **`tryAcquire` now throws on a server-error NULL response** (MySQL/MariaDB `GET_LOCK` returning
   NULL) instead of returning `Optional.empty()`. Previously a backend fault was reported as
   ordinary contention, so a caller's fallback path ran as if another holder were simply ahead of
   it. Contention still returns `Optional.empty()`.
2. **Backend failures now raise `LockAcquisitionException`** rather than a bare `RuntimeException`.
   Anything catching `RuntimeException` still works; anything matching on the old message strings
   does not.

Also signature-affecting, though source-compatible for callers that already handle it:
`acquire(String, Duration)` on the three SQL providers now declares `throws InterruptedException`,
matching the interface and the in-process/Redis implementations.
