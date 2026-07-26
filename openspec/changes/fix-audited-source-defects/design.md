# Java source audit - 2026-07-26

This document is the investigation record behind `proposal.md`. It states how the audit was run,
what it found, what it refuted, and what it never looked at. Line numbers are as of commit
`23b3a9a` (`EKB-0070 improvement on KeyedLockProvider`).

## Methodology

Eleven failure lenses were run over `src/main/java` of the framework modules (examples, tests,
and the Kotlin build logic were excluded). Each lens produced candidate findings independently;
65 candidates resulted.

Every candidate was then re-traced by skeptics instructed to **refute by default**. Critical and
high candidates got two skeptics with different mandates: one tracing control flow from the
quoted line outward, one hunting the repo for evidence that the behavior is intended (javadoc,
comments, `docs/`, `AGENTS.md`, `openspec/`, tests that pin it).

Outcome: **49 confirmed, 14 refuted, 2 below the verification cutoff.** Severity ratings below
are the verifiers' after downgrade, not the finders'. The 49 dedupe to roughly 35 distinct
defects - three lenses independently hit `DatabaseRegistry`.

The verification was empirical where it could be:

- SQL rendered through jOOQ 3.20.10 and executed against a live PostgreSQL 16 container.
- The shard bit-packing compiled and brute-forced over all 16,384 `(group, member)` pairs.
- smallrye-config 3.16.0 sources unpacked and its name converter executed on real env-var names.
- HikariCP 7.0.2 and Redisson 3.39.0 sources read to confirm pool and lock behavior.

An earlier comprehension sweep of the project had produced a list of "gotchas". Those were **not**
verified and should not be treated as findings: this audit **refuted** one of them
("`ActionExecutor.Builder.clock` has no null check, NPEs later").

## Findings register

### 1. PostgreSQL batch update discards column DataTypes

`ekbatan-core/.../repository/AbstractRepository.java:587` - **high**, both verifiers held.

```java
final var rows = records.stream().map(m -> DSL.row(m.intoArray())).toArray(RowN[]::new);
```

`m.intoArray()` returns user-type values, and `DSL.row(Object...)` re-infers a `DataType` from
each value's runtime class (`Tools.fieldsArray` -> `Tools.field(T)` -> `DSL.val0(value, true)`).
The target field's `DataType`, and with it any codegen `Converter`, is discarded. The
`values.field(name, targetField.getType())` lookup in `setField` (line 898) is a `coerce`, which
is compile-time only and does not restore the bind type - confirmed by rendering.

The sibling MariaDB builder at line 618 does it correctly with `DSL.val(record.get(field), field)`,
and `addAll`/`addAllNoResult` use `insertInto(table, fields).values(...)`, also typed. The
PostgreSQL batch update is the only path that gets this wrong.

Two proven consequences:

- An `Instant` is inferred as `SQLDataType.INSTANT`, documented as backed by
  `TIMESTAMP WITH TIME ZONE`, against the framework's mandated `TIMESTAMP` columns. Under a
  non-UTC JVM this shifts `created_date`/`updated_date` by the local offset (10:00 UTC persisted
  as 05:00 under `America/New_York`), while `update()` on the same column writes it correctly.
- A nullable non-text column that is null in *every* row of the batch fails with SQLSTATE 42804.

Blast radius: `updateAllNoResult` short-circuits `size() == 1` to the correctly-typed single-row
path (line 555), so this is reached only when an action stages two or more updates of the same
entity type. The canonical single-aggregate action is safe. But the path silently drops *any*
converter, which on PostgreSQL includes `JSONB` <-> `ObjectNode`.

Fix: build the rows from `DSL.val(m.get(f), f)` over `fields`. `DSL.row(Object...)` passes through
anything that is already a `Field`, so this preserves both `DataType` and `Converter`, and it
resolves both symptoms at once.

### 2. `rollback()` can commit the transaction it failed to roll back

`ekbatan-core/.../persistence/Transaction.java:56` - **medium**.

```java
try { connection.rollback(); }
finally { connection.setAutoCommit(initialAutoCommit); }   // runs even when rollback() threw
```

`initialAutoCommit` is captured at line 22 and is `true` under Hikari defaults; `begin()` sets it
`false` at line 32. The `finally` therefore performs a real `false -> true` transition, which
`java.sql.Connection.setAutoCommit` specifies as committing an in-progress transaction and which
MySQL/MariaDB document as an implicit `COMMIT` ("Statements That Cause an Implicit Commit").
HikariCP 7.0.2 passes `setAutoCommit` straight to the driver.

The trigger requires `ROLLBACK` to fail on a live session: `KILL QUERY` against the in-flight
rollback raises error 1317 `ER_QUERY_INTERRUPTED` and, unlike `KILL CONNECTION`, leaves the
transaction open; or a MariaDB Galera node returns "WSREP has not yet prepared node for
application use".

The irony is load-bearing: the comment at lines 59-67 is the only place in the repo that mentions
an implicit commit, and it names `setAutoCommit(true)` on a not-rolled-back transaction as the
hazard the dirty-flag guard defends against. The `finally` performs exactly that call, on exactly
that path, before the guard is reached.

Fix: restore autocommit only after a successful `rollback()`. Apply the same ordering in
`commit()`.

### 3. An `Error` from one handler discards the whole batch

`ekbatan-events/local-event-handler/.../job/EventHandlingJob.java:265` - **medium**.

`classify()` catches `InterruptedException` (line 260) and `Exception` (line 265), but not
`Error`. A `NoClassDefFoundError` from a missing optional runtime dependency, an `AssertionError`
under `-ea`, or a `StackOverflowError` in recursive domain code escapes the per-notification fork,
surfaces at `f.get()` (line 199) as `ExecutionException`, and is rethrown at line 206 as
`RuntimeException("Handling worker threw", ...)` - **upstream of all four bucket writes**
(`markExpiredAllPreflight` 236, `markSucceededAll` 237, `markExpiredAllPostFailure` 238,
`markFailedBucket` 243).

The whole batch is therefore discarded with zero rows written: successes are never marked
`SUCCEEDED`, failures never `FAILED`, `attempts` is not bumped and `next_retry_at` is not moved.
`findDue` orders by `next_retry_at`, so the poison row keeps its place at the head and is in every
subsequent batch. That shard's delivery stops advancing.

Both verifiers confirmed the mechanism and **corrected the finder's overclaim** that recovery
needs manual DB surgery - it does not; removing the cause is enough.

Fix (either suffices, both preferred): catch `Throwable` in `classify()` (optionally rethrowing
`VirtualMachineError`); and make the bucket writes unconditional with respect to invocation
failures, so whatever was already decided for the rest of the batch is still committed.

### 4. Quarkus env-var binding is broken, two ways

`ekbatan-di/quarkus/runtime/.../EkbatanCoreConfiguration.java:73` and `:78` - **medium** each,
both reproduced end to end. Both defects affect **both** copy loops: `:73-79`
(`ekbatanShardingConfig`) and the shared `bindSubtree` helper at `:140-146` (used by
`ekbatanJobsConfig` and `ekbatanLocalEventHandlerConfig`).

**(a) Synthesized names are copied verbatim as Jackson keys.** SmallRye's `EnvConfigSource`
registers two names per environment variable - the raw name and
`StringUtil.toLowerCaseAndDotted(raw)` - and `getPropertyNames()` returns both.
`PropertyKeyNormalizer.kebabToCamel` short-circuits on any hyphen-free key, so the dotted form
passes through untouched. The binder looks the value up under the original name (which resolves
correctly) but writes it under the *enumerated* name. Executed against the real converter:

```
EKBATAN_SHARDING_GROUPS_0_MEMBERS_0_CONFIGS_PRIMARYCONFIG_PASSWORD
  -> ekbatan.sharding.groups[0]members[0]configs.primaryconfig.password
```

Under `FAIL_ON_UNKNOWN_PROPERTIES` the strict `JavaPropsMapper` rejects that, and the app dies at
boot with `Failed to bind 'ekbatan.sharding' configuration to ShardingConfig`. Injecting a DB
secret via environment variable - the standard container practice - therefore cannot work.

Fix: do not trust enumerated names as canonical keys. Either filter out names that are env-var
syntheses, or invert the loop and look up each expected canonical key with
`config.getOptionalValue(...)`, which resolves environment variables correctly.

**(b) Empty values are silently dropped.** SmallRye's `String` converter is
`newEmptyValueConverter(new StringConverter())` with a `null` empty value
(`Converters.java:75`, `:353-355`, `:1144-1153`), so `OptionalConverter.convert("")` yields
`Optional.empty()` and `ifPresent` skips the property. A MySQL/MariaDB dev or CI deployment
setting `...primaryConfig.password=` (root with no password) hits
`Validate.notNull(builder.password, "password is required")` and dies - even though the user did
set it, and even though `DataSourceConfig.java:104` documents the value as "Required (may be the
empty string)". The identical configuration binds fine on Spring (`environment.getProperty`
returns `""`) and Micronaut (`v != null` passes).

Fix: `var cv = config.getConfigValue(name); if (cv.getValue() != null) props.setProperty(sub, cv.getValue());`

### 5. `InProcessKeyedLockProvider.tryAcquire` leaks its refcount on interrupt

`ekbatan-core/.../concurrent/InProcessKeyedLockProvider.java:65` - **medium**.

```java
var entry = retainEntry(key);
boolean acquired = entry.semaphore.tryAcquire(maxWait.toNanos(), TimeUnit.NANOSECONDS);
if (!acquired) { releaseEntry(key); return Optional.empty(); }
```

`releaseEntry` sits only on the timeout branch; there is no try/catch/finally. The sibling
`acquire()` at lines 45-48 *does* have `catch (InterruptedException e) { releaseEntry(key); throw e; }`,
so the compensation is missing only here. `releaseEntry` evicts the map entry only when
`decrementAndGet() == 0`, so a leaked `+1` pins the `LockEntry` for the JVM's lifetime and
`activeKeyCount()` can never return to zero - contradicting the class javadoc's removal guarantee
at lines 19-21.

The verifier broadened the trigger: `AbstractQueuedSynchronizer.tryAcquireSharedNanos` tests
`Thread.interrupted()` before attempting the permit, so **any** thread entering with its interrupt
flag already set leaks - uncontended, even with `maxWait = Duration.ZERO`. With the documented
`"wallet:" + walletId` key pattern, the map grows without bound.

Fix: a single try/finally releasing the entry on every non-success exit (timeout, interrupt, and
any `RuntimeException` from `register()`).

### 6. Also confirmed

| Where | Defect | Severity |
|---|---|---|
| `KeyedReentrantHolder.java:136` | The watchdog body catches only `InterruptedException`, so a `RuntimeException`/`Error` from `lockReleaseCallback.release(..., WATCHDOG)` at line 134 escapes the `Runnable` and kills the virtual thread via the default uncaught handler - stderr only, never SLF4J. `markReleasedByWatchdog()` and `map.remove` have already run, so every lease reports `isHeld() == false` while the backend still holds the lock, and `close()` short-circuits so no second attempt is made. The `LOG.warn` at line 132 claims the auto-release succeeded before it is attempted. The trigger is real: the JDBC providers' connection-return step throws `RuntimeException("Failed to release connection", ...)` (`ConnectionProvider.java:51-59`). | low |
| `RedisKeyedLockProvider.java:112` | `backendRelease` catches every `RuntimeException` and logs at DEBUG with text asserting the opposite of what happened ("it was no longer held"). Redisson 3.39.0 surfaces both a genuine backend failure and the benign owner-check failure as `CompletionException`, so they are indistinguishable. A Redis outage during release is invisible and the key stays locked for the remaining TTL, blocking every other node. Fix: unwrap and branch - `IllegalMonitorStateException` stays DEBUG, everything else WARN/ERROR. | medium |
| `RedisKeyedLockProvider.java:73` | A sub-millisecond `maxHold` truncates to `leaseTime = 0`, which re-enables Redisson's renewal watchdog - the key is renewed indefinitely and never unlocked. | medium |
| `DatabaseRegistry.java:155` | `secondaryConfig().orElse(primaryConfig)` falls back on the *config object*, then the factory is invoked a second time unconditionally, producing a second independent Hikari pool against the identical URL and credentials when no replica is configured. A no-replica deployment holds `2 x maximumPoolSize` connections plus two sets of housekeeping threads per shard: 8 shards at 20 gives 320 where the operator budgeted 160. Breaks the documented invariant at `DatabaseRegistry.java:46`, `docs/database/sharding.md:279`, and the `TransactionManager` primary-constructor javadoc (which is why `close()` carries the `!=` identity guard at `TransactionManager.java:218`). Fix is one line: `member.secondaryConfig().map(ConnectionProvider::hikariConnectionProvider).orElse(primaryProvider)`. | low |
| `DatabaseRegistry.java:167` | Every already-started pool leaks when construction fails partway, including on a duplicate `(group, member)`. | low |
| `EventFanoutJob.java:184` | `drainBatch` returns `events.size()` - rows *read from the replica* (`readonlyDb`) - as the round's progress signal and as `EVENTS_FANNED_OUT`, while `markDelivered` writes to primary with a `WHERE id IN (...)` that has no `delivered = false` predicate. With a distinct replica pool and replication lag, the next round re-reads the same rows, `any` stays true, `execute()` skips `Thread.sleep(pollDelay)`, and the loop busy-spins re-inserting (absorbed by `ON CONFLICT DO NOTHING`) and re-updating already-delivered rows - each spin writing fresh tuple versions and WAL, growing the very lag it waits on. Fix: derive the signal and the metric from rows actually written, and add `.and(DELIVERED.eq(false))`. | low |
| `Retry.java:36` | One global attempt counter shared across all configured exception types, so a per-type `RetryConfig` budget can be consumed by a different type. | low |
| `PostgresKeyedLockProvider.java:77` | `Thread.interrupted()` clears the flag before the first `tryAcquire`, so the reentry path loses the interrupt status. Found independently and confirmed by the audit. | low |
| `MySQLKeyedLockProvider.java:106` | `connectionProvider.acquire()` failing - the database being unreachable, the canonical backend failure - still escapes as a bare `RuntimeException`, exactly what `LockAcquisitionException` (EKB-0070) was introduced to eliminate. | low |
| `AutoBuilderProcessor.java:106/110/150` | Crashes javac with `IndexOutOfBoundsException` instead of emitting a `Messager` error for a non-direct `Model`/`Entity` subclass; omits type variables for generic domain classes; no getter name-collision check. | low |
| `Jackson3RecordsFeature.java:266` | Missing the `NoClassDefFoundError` guard that its three sibling `Feature`s document as mandatory - one unresolvable member aborts the whole native-image build. | low |
| `FlywayMigrator.java:162` | The native resource provider captures `locations` *before* the user customizer runs, contradicting the documented ordering guarantee: a customizer that changes locations is silently ignored on native. | low |
| `EkbatanActionsHolder.java:26` | Process-global static, never reset - one context's AOT action list leaks into another in the same JVM. | low |
| `DataSourceConfig.java:56` | `resolveDialect` matches `mysql` anywhere in the URL *and* before `mariadb`, so `jdbc:mariadb://mysql-host/db` silently resolves to `SQLDialect.MYSQL`. | low |
| `SingleTableJsonEventPersister.java:101` | Payload blind-cast to `ObjectNode` with none of the validation the sibling `actionParams` path performs. | low |
| Quarkus `@IfBuildProperty` | The build-time gate uses the literal kebab key while the binder also accepts the camelCase alias, so `handling.enabled=true` can leave the job switched off. | low |

### 7. Missing builder validation cluster

`AGENTS.md` requires all validation in the target constructor even when the builder supplies a
default. These violate it:

- `DataSourceConfig` validates no numeric field: `maximumPoolSize = 0` is accepted, a negative
  `leakDetectionThreshold` silently disables detection.
- `ShardGroupConfig` / `ShardMemberConfig` skip the ranges `ShardIdentifier` mandates
  (group 0..255, member 0..63).
- `RetryConfig` accepts a negative delay.
- `EventNotification.attempts` is unbounded.
- `Optional.of` in setters turns an explicit null into a message-less NPE.

## What came back clean

Stated because it is most of the codebase, and because "no findings" is only meaningful when the
lens actually ran:

- **Shard bit arithmetic is correct** - verified exhaustively over all 16,384 `(group, member)`
  pairs; UUID version `0b0111` and variant `0b10` preserved at both extremes; no sign-extension
  or int-promotion bugs.
- **`ScopedValue` plumbing is sound** - no plan leaks between attempts or threads, `plan()`
  correctly throws outside scope, no mutable state on `Action` singletons, OTel spans closed on
  every exit path.
- **`notDeleted()` is applied on every read path without exception**; empty and single-element
  inputs are guarded throughout the repository layer; the duplicate-ID staging guard is intact.
- **The EKB-0070 requirements are genuinely met** - 5s segmentation, interrupt re-checked at
  every boundary with no holder entry or connection left behind, `LockAcquisitionException`
  carrying key and cause, `GET_LOCK` NULL now throwing, suppressed cleanup on error paths. The
  loop cannot spin. The keyed-lock defects above are at the *edges the rewrite moved*, not in
  what it set out to do.
- **14 plausible claims were refuted**, including two that sounded credible: "unregistered-shard
  degradation strands writes" and "`EmbeddedBitsShardingStrategy` skips the v7 guard".

## Not audited - disclosed gaps

The lens set did not cover these. "No findings" would be false for them; they are simply
unexamined:

- `ekbatan-distributed-jobs` - `JobRegistry`, `DistributedJob`, `JobsConfig` (~360 LOC).
- The Debezium SMT transforms and the `action-event` wire POJOs under `ekbatan-events/streaming/`.
- Example-app Java, test code, and the Kotlin build logic (out of scope by design).

A follow-up sweep of the first two is worth scheduling before 1.0.0.

## Separately tracked

The same investigation surfaced documentation and metadata drift (stale Gradle task paths in
`AGENTS.md`, a partial-index name that disagrees three ways, a `retry.count` / `retry.attempt`
span-attribute conflict, broken links to a non-existent `docs/wiring/with-di.md`, website pages
with no `docs/` counterpart, and a stale copied README under
`spring-boot-job-worker-gradle-pg/`). Those are **not** in this change's scope and should get
their own.
