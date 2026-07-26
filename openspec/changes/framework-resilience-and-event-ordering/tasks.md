## 1. Cross-Shard Action Failure Logging

- [x] 1.1 Update `ActionExecutor.persistChanges()` to track `committedShards` set during multi-shard persistence loop.
- [x] 1.2 Add explicit structured warning logging (`LOG.error`) on persistence failure detailing action name, failing shard, committed shards, and exception context. Also sets three `ekbatan.shard.*` attributes on the `ekbatan.action.persist` span, and passes the throwable to SLF4J so operators get a stack trace.
- [x] 1.3 Add unit/integration tests verifying partial cross-shard failure handling:
  - `ekbatan-core` `ActionExecutorTracingTest` — `partial_cross_shard_failure_is_recorded_on_persist_span` asserts the three span attributes + cross-shard flag + ERROR status; `failure_before_any_shard_commits_is_not_flagged_as_partial` pins the `committedShards.isEmpty()` guard.
  - `ekbatan-integration-tests-postgres-sharded` `ShardedWalletIntegrationTest` — `cross_shard_partial_commit_keeps_committed_shard_and_rolls_back_the_failing_one` proves end-to-end over two real Postgres shards that the committed shard is **not** rolled back.
  - Note: the ERROR log line itself is asserted indirectly, via the span attributes written on the same code path. Literal log capture would require adding an SLF4J binding (logback) to a test module — no test classpath in this repo has one today, and there is no precedent for log assertions anywhere in the suite.

## 2. Keyed Lock Acquire Semantics (SQL-backed providers)

> Rescoped after reviewing the implementation. The original tasks (pre-flight liveness checks,
> release-path hardening) targeted behavior that is already implemented and covered by 86 passing
> tests — see the "Explicitly out of scope" section of the spec. These tasks target the defects
> that are actually present.

**Typed exception**

- [x] 2.1 Add `io.ekbatan.core.concurrent.LockAcquisitionException` (extends `RuntimeException`), carrying the lock key and the underlying cause. Settles the naming: the spec previously said `PersistenceException` / `LockAcquisitionException` and `proposal.md` said `LockLostException` — none of the three exist in the codebase.
- [x] 2.2 Replace the bare `RuntimeException` throws in `acquire` / `tryAcquire` of `PostgresKeyedLockProvider`, `MySQLKeyedLockProvider`, `MariaDBKeyedLockProvider` with `LockAcquisitionException`. Leave the "lock unavailable" path returning `Optional.empty()`.

**Stop masking the original failure**

- [x] 2.3 In every acquire-failure catch block, wrap the `connectionProvider.release(...)` / `evict(...)` cleanup in its own try/catch and attach any cleanup failure via `addSuppressed(...)`, so a throwing `ConnectionProvider.release` can no longer replace the original `SQLException` cause.

**Honor the interruptible blocking contract**

- [x] 2.4 Reimplement `acquire(key, maxHold)` in the three SQL providers as a loop over the existing `tryAcquire` path with a **5 second** segment constant, checking `Thread.interrupted()` between segments. Add `throws InterruptedException` to each signature so it matches `KeyedLockProvider`, `InProcessKeyedLockProvider`, and `RedisKeyedLockProvider`. Buys two things: interruptibility (the declared contract becomes true), and traffic every 5s so idle-connection reapers stop silently killing long lock waits. Precedent: Spring Integration `JdbcLockRegistry` polls the same way via `idleBetweenTries`.
- [x] 2.5 Verify no holder state or borrowed connection leaks on the interrupt path (`KeyedReentrantHolder` entry absent, connection returned/evicted).

**Tests**

- [x] 2.6 Unit tests (`ekbatan-core`, Mockito, alongside the existing 86): interrupt during a blocked acquire throws `InterruptedException`; backend error yields `LockAcquisitionException` with cause; a throwing `release` during acquire-failure handling preserves the original cause and records the cleanup failure as suppressed.
- [x] 2.7 Integration tests (`keyed-lock-provider/{pg,mysql,mariadb}`, Testcontainers): a thread blocked in `acquire` behind a holder is released by interrupt; a server-side termination (`pg_terminate_backend` / MySQL `KILL`) surfaces as `LockAcquisitionException` with no holder leak; the unchanged blocking contract still holds (waiter acquires after the holder releases). Note: server-side termination already fails promptly today — assert it to prevent regression, not as proof the loop fixed it.

**Docs**

- [x] 2.8 Fix `docs/database/keyed-locks.md:25` — it promises "Waits indefinitely until acquired (or thread interrupt)", which the three SQL providers do not currently deliver. Document the poll-chunk implementation and the loss of database-level FIFO queueing under contention.
- [x] 2.9 Mirror the same correction in `website/src/pages/reference/keyed-locks.mdx:28` — verified to repeat the claim verbatim.
- [x] 2.10 Document the known limitation in `docs/database/keyed-locks.md`: the segmented wait stops idle-connection reapers from killing long lock waits, but a network partition landing mid-segment still parks the caller; that is not lock-specific and is left to `socketTimeout` on the lock pool's JDBC URL. Also state the FIFO/starvation caveat, in the javadoc as well as the docs.
- [x] 2.11 `KeyedLockProvider` javadoc lines 71 and 90 both claim `@throws InterruptedException if the calling thread is interrupted while waiting`. Line 71 (`acquire`) becomes true once 2.4 lands. Line 90 (`tryAcquire`) stays false for the SQL providers — their wait is bounded by `maxWait` so it always terminates, but it is not interruptible mid-wait. Either make `tryAcquire` interrupt-aware too, or soften the javadoc for it.

**Already satisfied — no work required**

- [x] ~~Release-path graceful degradation~~ — `lockRelease` / `backendRelease` already catch `SQLException`, log, and evict; `HeldLease.close()` already clears the holder entry and interrupts the watchdog *before* the backend callback. Tested by `close_lease_should_evict_connection_when_unlock_fails`, `close_lease_should_evict_connection_when_release_lock_throws`, and siblings.
- [x] ~~`RedisKeyedLockProvider` connection liveness~~ — no JDBC connection involved; `acquire` is already interruptible via `lockInterruptibly`. Dropped from scope.

## 3. Per-Entity In-Order Local Event Dispatch — DEFERRED

**Parked, not delivered. No open work.** See
`specs/in-order-local-event-dispatch/spec.md` for the full analysis.

Short version: the problem is real (`EventHandlingJob` does dispatch same-entity events
concurrently), but the framework already answers it twice — guard-based idempotent handlers
in-process, `model_id` partition keys on the CDC path — ordering-plus-retries-plus-bounded-latency
is a trilemma, and no concrete driver exists. Four design questions must be settled before any
revival, the blocking one being whether a failing event performs head-of-line blocking on its
entity (true ordering, but a poison event stalls that entity for up to the 7-day retention window)
or is overtaken (guarantee lost).

The tasks below are recorded for reference only. Do not pick them up without first answering
question 1 in the spec.

- ~~3.1 Add `sequentialPerEntity` boolean property to `LocalEventHandlerConfig` (default `false`).~~
- ~~3.2 Update Spring Boot, Quarkus, and Micronaut autoconfiguration classes to bind `ekbatan.local-event-handler.sequential-per-entity`.~~
- ~~3.3 Update `EventHandlingJob` dispatch logic to group due notifications by `model_id`.~~
- ~~3.4 Add integration tests in `ekbatan-integration-tests/local-event-handler-shared`.~~
