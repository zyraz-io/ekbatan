# Keyed lock acquire semantics

> **Rescoped during review** (and renamed from `keyed-lock-liveness-detection`). The original
> premise — pre-flight `isClosed()` / `isValid()` checks before acquiring — did not survive
> review: the release path already degrades gracefully and is covered by existing passing tests,
> and a pre-flight liveness check cannot prevent the hang it was meant to prevent (the connection
> is alive when checked and dies during the subsequent unbounded wait). The requirements below
> target what is actually broken.

## Context

`KeyedLockProvider` declares:

```java
Lease acquire(String key, Duration maxHold) throws InterruptedException;
```

`InProcessKeyedLockProvider` (fair `Semaphore`) and `RedisKeyedLockProvider`
(`lockInterruptibly`) honor that contract. The three SQL-backed providers do not — they do not
even declare `throws InterruptedException`, because they block inside a JDBC socket read that
`Thread.interrupt()` cannot break. A caller blocked in `PostgresKeyedLockProvider.acquire(...)`
therefore has no escape at all: the wait is unbounded server-side, it is not interruptible, it
pins a pooled JDBC connection for the entire duration, and with no `socketTimeout` on the URL a
silently-dropped TCP connection parks the thread indefinitely.

`docs/database/keyed-locks.md` additionally promises an interrupt escape hatch — "Waits
indefinitely until acquired (or thread interrupt)" — that these three providers do not deliver.

## ADDED Requirements

### Requirement: Blocking acquire on SQL-backed providers honors the interruptible contract

`PostgresKeyedLockProvider`, `MySQLKeyedLockProvider`, and `MariaDBKeyedLockProvider` SHALL
implement `acquire(key, maxHold)` so that it blocks until the lock is available while remaining
responsive to thread interruption. Each SHALL declare `throws InterruptedException`, matching the
interface and the other two implementations.

The wait SHALL be composed of bounded segments rather than one unbounded call, so that interruption
is observed between segments and the connection does not sit silent for the duration of the wait.

#### Scenario: Blocked acquire is interrupted

- **WHEN** a thread is blocked in `acquire(key, maxHold)` waiting behind another holder, and that
  thread is interrupted
- **THEN** the call SHALL throw `InterruptedException`, release any borrowed connection, and leave
  no `KeyedReentrantHolder` entry for `(thread, key)`

#### Scenario: Backend connection is terminated by the server

- **WHEN** the JDBC connection serving a blocked `acquire(key, maxHold)` is terminated by the
  database server (for example `pg_terminate_backend`)
- **THEN** the call SHALL fail with `LockAcquisitionException` and SHALL NOT leave a
  `KeyedReentrantHolder` entry behind

#### Scenario: Blocking acquire still blocks until available

- **WHEN** a thread calls `acquire(key, maxHold)` for a key currently held by another thread, and
  the holder releases it before any interruption
- **THEN** the waiting thread SHALL acquire the lock and receive a held `Lease` — the observable
  blocking contract is unchanged

### Requirement: Lock acquisition failures raise a dedicated exception type

All `KeyedLockProvider` implementations SHALL signal a failed acquisition with
`io.ekbatan.core.concurrent.LockAcquisitionException`, carrying the lock key and the underlying
cause, rather than a bare `RuntimeException`. Callers SHALL be able to distinguish "the backend
failed" from "the lock was not available"; the latter remains `Optional.empty()` from
`tryAcquire`, never an exception.

#### Scenario: Backend failure during acquire

- **WHEN** the backend raises an error while `acquire(key, maxHold)` is obtaining the lock
- **THEN** the provider SHALL throw `LockAcquisitionException` naming the key, with the backend
  error as its cause

#### Scenario: Backend failure during tryAcquire

- **WHEN** the backend raises an error while `tryAcquire(key, maxWait, maxHold)` is obtaining the
  lock
- **THEN** the provider SHALL throw `LockAcquisitionException` naming the key, with the backend
  error as its cause

#### Scenario: Lock simply unavailable

- **WHEN** `tryAcquire(key, maxWait, maxHold)` completes its wait without obtaining the lock and no
  backend error occurred
- **THEN** the provider SHALL return `Optional.empty()` and SHALL NOT throw

### Requirement: Connection cleanup failure never masks the original error

When a provider releases or evicts its borrowed connection while handling a failed acquisition, a
failure of that cleanup SHALL NOT replace the original backend error. The original error SHALL
propagate, with the cleanup failure attached as a suppressed exception.

#### Scenario: Connection release throws while handling an acquire failure

- **WHEN** `acquire` or `tryAcquire` fails with a backend error, and returning the connection to
  the pool itself throws
- **THEN** the caller SHALL receive `LockAcquisitionException` caused by the original backend
  error, with the cleanup failure available via `getSuppressed()`

## Explicitly out of scope

- **Pre-flight `isClosed()` / `isValid()` checks.** Connections are borrowed per acquire from
  HikariCP, which validates on borrow; a pre-flight check duplicates that and does not address the
  unbounded-wait hang, which is the real failure mode.
- **Release-path hardening.** Already implemented and tested. `lockRelease` / `backendRelease`
  catch `SQLException`, log a structured error, and evict; `KeyedReentrantHolder.HeldLease.close()`
  removes the holder entry and interrupts the watchdog *before* invoking the backend callback, so
  reentrancy state and the watchdog are cleaned up even if the backend call fails. Covered by
  `close_lease_should_evict_connection_when_unlock_fails` and siblings.
- **`RedisKeyedLockProvider`.** It holds a Redisson `RLock`, not a JDBC connection, and its
  `acquire` is already interruptible. Excluded from the interruptibility requirement; included in
  the typed-exception requirement only where it already throws.

## Known limitation: silently-dropped connections

Two different failures put a connection out of action, and this capability addresses only one.

**Idle-connection reaping** — NAT gateways, load balancers, and PgBouncer drop entries they have
seen no traffic on, typically after 5-60 minutes. An unbounded `pg_advisory_lock` sends zero packets
for the whole wait, so a long lock wait looks identical to an abandoned connection and gets reaped.
The segmented wait fixes this: traffic every 5 seconds keeps the entry alive, so the reaper never
fires. **This is the lock-specific failure mode, and it is addressed.**

**Network partition** — a cable, route, or VPC failure landing *inside* a segment leaves the caller
blocked in a socket read, and no client-side loop can bound that. Only a socket read timeout can.
This is deliberately **not** addressed here, because it is not lock-specific: any long-running JDBC
call has identical exposure, and hardening only the lock path would be inconsistent. Operators who
want it can set `socketTimeout` in the JDBC URL of the lock pool.

## Risks / trade-offs

- **Loss of database-level FIFO ordering.** During each segment the waiter is genuinely queued
  inside the database (`SET lock_timeout` + `pg_advisory_lock`), so hand-off stays immediate — this
  is not client-side spinning. The cost is that each re-entry puts the waiter at the *back* of that
  queue, so a long-waiting thread repeatedly yields position to newer arrivals. Under sustained
  contention that is a starvation risk. Cross-JVM fairness was already documented as best-effort
  (`InProcessKeyedLockProvider` is the only implementation promising FIFO), but this weakens it
  further and must be stated in the docs and in the javadoc.
- **Segment length is an interrupt-latency vs fairness trade-off.** Shorter means faster interrupt
  response and more queue re-entries; longer means the reverse. **5 seconds** is the chosen
  constant: it keeps interrupt latency well inside typical 30s graceful-shutdown budgets
  (Kubernetes `terminationGracePeriodSeconds`, Spring Boot shutdown), and it is far below any
  realistic idle-reaper threshold. A single documented constant is preferred over a configuration
  knob until there is evidence one is needed.
- **Precedent.** Spring Integration's `JdbcLockRegistry` — the closest equivalent JDBC-backed
  distributed lock — also polls, via `idleBetweenTries` (default 100ms). Polling is the standard
  approach for this problem; the variant here is strictly better on hand-off latency because the
  waiter parks in the database's own queue during each segment instead of sleeping client-side.
