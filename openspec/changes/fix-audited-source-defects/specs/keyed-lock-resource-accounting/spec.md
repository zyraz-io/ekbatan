# Keyed lock resource accounting

## Context

EKB-0070 (`framework-resilience-and-event-ordering`) fixed the acquire *semantics* of the SQL
providers - interruptibility, typed failures, non-masking cleanup - and those requirements are
genuinely met. The defects below are at the edges that rewrite moved, plus pre-existing gaps in
the shared holder and the Redis provider. They are about **resource accounting and
observability**, not about acquire semantics.

See `design.md` findings 5 and 6.

## ADDED Requirements

### Requirement: No lock path leaks a refcount or a pooled connection

Every exit from a `KeyedLockProvider` acquire attempt - success, contention timeout, interrupt,
or any exception from registration - SHALL leave the provider's bookkeeping exactly as it was
before the attempt, except for a successful acquire.

#### Scenario: Interrupted in-process tryAcquire

- **GIVEN** a thread whose interrupt flag is already set, or which is interrupted while waiting
- **WHEN** it calls `InProcessKeyedLockProvider.tryAcquire(key, maxWait, maxHold)`, contended or
  not, including with `maxWait = Duration.ZERO`
- **THEN** `InterruptedException` SHALL be thrown
- **AND** the per-key refcount SHALL be decremented, so `activeKeyCount()` returns to zero once
  no holder or waiter remains, as the class javadoc promises

#### Scenario: Registration failure on a SQL provider

- **WHEN** `KeyedReentrantHolder.register` fails after the backend lock has been acquired
- **THEN** the backend lock SHALL be released and the borrowed connection returned or evicted

#### Scenario: Interrupt does not consume the interrupt status

- **WHEN** `PostgresKeyedLockProvider.acquire` observes an interrupt
- **THEN** it SHALL throw `InterruptedException` without leaving the thread's interrupt status
  ambiguous for the reentry path

### Requirement: A failed release is always observable

No release path SHALL report success when the backend lock may still be held. A release failure
SHALL be logged at WARN or ERROR with its cause, and log text SHALL never assert an outcome that
was not established.

#### Scenario: Watchdog force-release fails

- **GIVEN** a lease that overruns `maxHold`
- **WHEN** the release callback throws `RuntimeException` or `Error` - for example the JDBC
  providers' `RuntimeException("Failed to release connection", ...)`
- **THEN** the watchdog thread SHALL NOT terminate through the default uncaught handler
- **AND** the failure SHALL be logged at ERROR through SLF4J, stating that the backend lock may
  remain held
- **AND** the "auto-released" log line SHALL not be emitted before the release is attempted

#### Scenario: Redis release fails against an unreachable server

- **WHEN** `RedisKeyedLockProvider` release fails with anything other than a benign owner-check
  failure
- **THEN** it SHALL be logged at WARN or ERROR stating that the key remains held until its TTL
  expires
- **AND** the benign `IllegalMonitorStateException` case MAY remain at DEBUG, worded so it does
  not claim the failure case

### Requirement: Backend failures raise LockAcquisitionException uniformly

Every backend failure during acquisition on every SQL provider SHALL surface as
`LockAcquisitionException` carrying the key and the cause, including failures obtaining the
connection itself.

#### Scenario: Database unreachable

- **WHEN** `connectionProvider.acquire()` fails in `MySQLKeyedLockProvider`
- **THEN** the caller SHALL see `LockAcquisitionException`, not a bare `RuntimeException`

### Requirement: maxHold is honored at sub-millisecond precision

A positive `maxHold` SHALL never be truncated to a value that changes the release semantics of
the backend.

#### Scenario: Sub-millisecond maxHold on Redis

- **WHEN** `RedisKeyedLockProvider` is given a positive `maxHold` below one millisecond
- **THEN** the lease SHALL NOT be created with `leaseTime = 0`, which would re-enable Redisson's
  renewal watchdog and keep the key locked indefinitely
