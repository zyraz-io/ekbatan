## Why

A full adversarial audit of the framework's Java sources (2026-07-26) produced 49 verified
defects, roughly 35 distinct after dedup. None of them were found by the existing test suite.
Several are silent-data-correctness or availability defects that a 1.0.0 release should not carry:

1. The PostgreSQL batch-update builder discards each column's `DataType` (and therefore its
   codegen `Converter`), so `Instant` columns can be written with the wrong time zone and
   all-null nullable columns fail outright.
2. `Transaction.rollback()` restores autocommit in a `finally` that also runs when `ROLLBACK`
   threw - on MySQL/MariaDB that is a documented implicit `COMMIT` of the transaction it just
   failed to roll back.
3. An `Error` from a single local event handler discards its entire batch before any state write,
   so the poison row recurs on every poll and that shard's delivery stalls.
4. The Quarkus config binder cannot bind environment variables (SmallRye's synthesized dotted
   names are copied verbatim as Jackson keys and hard-fail `FAIL_ON_UNKNOWN_PROPERTIES`) and
   silently drops empty-string values, so a legal empty password dies with "password is required".
5. Assorted keyed-lock resource-accounting and observability defects: a leaked refcount on
   interrupt, a watchdog that dies silently, a Redis release failure logged at DEBUG with text
   asserting the opposite of what happened.

The audit is recorded in full in `design.md`, including methodology, per-finding mechanism, and
the areas deliberately left unexamined.

## What Changes

- Type the PostgreSQL batch-update `VALUES` table against the target fields, mirroring the
  MariaDB builder and the batch-insert path.
- Restore autocommit only after a successful `rollback()` / `commit()`; leave the connection
  dirty (and therefore evicted) otherwise.
- Isolate local event-handler faults at `Throwable` granularity and make the batch's bucket
  writes survive an abnormal fork.
- Make the Quarkus binder derive Jackson keys from canonical property names rather than from
  SmallRye's enumerated aliases, and read raw config values so empty strings survive.
- Close the keyed-lock resource-accounting and observability gaps (refcount on interrupt,
  watchdog fault handling, Redis release logging, sub-millisecond `maxHold`, bare
  `RuntimeException` from `connectionProvider.acquire()`).
- Fix the second-Hikari-pool duplication and the partial-construction pool leak in
  `DatabaseRegistry`.
- Apply the remaining lower-severity fixes catalogued in `design.md` (annotation processor
  diagnostics, native-image guards, dialect resolution, builder validation cluster).

## Capabilities

### New Capabilities

- `typed-batch-update-binding`: batch updates bind through the target field's `DataType` on
  every dialect.
- `transaction-cleanup-safety`: connection-state restoration never converts a failed rollback
  into a commit.
- `event-dispatch-fault-isolation`: one poisoned notification cannot stall a shard's delivery.
- `di-config-binding-parity`: Spring, Quarkus, and Micronaut bind the same configuration to the
  same result, including environment variables and empty values.
- `keyed-lock-resource-accounting`: no lock path leaks a refcount, a pooled connection, or a
  release failure.

### Modified Capabilities

<!-- None; the capabilities above are new contracts over existing code. -->

## Impact

- `ekbatan-core`: `AbstractRepository`, `Transaction`, `DatabaseRegistry`, `DataSourceConfig`,
  `Retry`, `InProcessKeyedLockProvider`, `KeyedReentrantHolder`, `PostgresKeyedLockProvider`,
  `MySQLKeyedLockProvider`, `SingleTableJsonEventPersister`, config builder validation.
- `ekbatan-keyed-lock-redis`: `RedisKeyedLockProvider`.
- `ekbatan-events:local-event-handler`: `EventHandlingJob`, `EventFanoutJob`,
  `EventNotificationRepository`, `EventEntityRepository`.
- `ekbatan-di`: Quarkus `EkbatanCoreConfiguration` and the `@IfBuildProperty` gates;
  Spring `EkbatanActionsHolder`, `Jackson3RecordsFeature`, `FlywayMigrator`.
- `ekbatan-processor`: `AutoBuilderProcessor`.
- Tests: new regression coverage in `ekbatan-core` unit tests and in the Postgres / MySQL /
  MariaDB integration-test modules; new binding tests in all three DI test suites.

### Backward compatibility

Behavior-preserving for correct callers. Three observable changes worth calling out:

1. `updateAll`/`updateAllNoResult` on PostgreSQL will render different (correctly typed) SQL.
   Any application that read `created_date` back and compensated for the offset shift will see
   the compensation become wrong - which is the point.
2. Quarkus applications that today work around the env-var defect by duplicating properties may
   find previously-ignored env vars taking effect.
3. `MySQLKeyedLockProvider` will raise `LockAcquisitionException` where it previously raised a
   bare `RuntimeException` on connection-acquisition failure. Consistent with EKB-0070.
