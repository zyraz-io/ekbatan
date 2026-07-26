# Tasks

Ordered by the audit's recommended fix sequence. Every item cites the finding in `design.md`.
Section 1 is complete; the rest have not been started.

## 1. Typed batch update binding (design.md finding 1)

- [x] 1.1 Replace `DSL.row(m.intoArray())` at `AbstractRepository.java:587` with rows built from
      `DSL.val(m.get(f), f)` over `fields`, mirroring `buildUpdateAllQueryMariadb` line 618.
      Verified by rendering through jOOQ 3.20.10: the bind changes from
      `cast(? as timestamp with time zone)` to `cast(? as timestamp)`, and an all-null column
      changes from a bare `null` to `cast(null as int)`.
- [x] 1.2 Audit the remaining `AbstractRepository` write paths for the same untyped pattern and
      confirm `addAll`/`addAllNoResult` and the single-row `update` are already typed.
      A repo-wide grep for `intoArray()` / `DSL.row(` now returns only the fixed site.
- [x] 1.3 Integration test: two updates of the same type round-trip an exact `Instant` for
      `created_date`. Written as `should_preserve_exact_timestamps_when_updateAll_batches_multiple_rows`
      in the shared `BaseRepositoryTest`, so PostgreSQL, MySQL and MariaDB all inherit it.
      Passing on all three; fails against the pre-fix code.
- [x] 1.3a Cover the non-UTC case. Resolved without touching the JVM default zone or adding a
      test task: the shift is applied server-side using the **session** `TimeZone`, so
      `should_preserve_exact_timestamps_when_the_session_is_not_utc` issues
      `SET LOCAL TIME ZONE 'America/New_York'` inside the transaction that performs the batch
      update. `SET LOCAL` reverts on commit, so the pooled connection is returned unchanged and
      no other test is affected.
      Lives in `PgRepositoryTest`, not the shared base. Two reasons: the defective builder is
      Postgres-only (MySQL and MariaDB use `buildUpdateAllQueryMariadb`, which always bound
      through the target field), and the test could not reproduce anything on those two anyway -
      they store these columns as `DATETIME(6)`, which has no time-zone semantics, so a session
      zone cannot move the value. Promoting it to the base class with a per-dialect hook was
      considered and rejected: it would have added an abstract method plus a save/restore
      obligation on MySQL/MariaDB (their `SET LOCAL` is a synonym for `SET SESSION` and does not
      revert on commit) in exchange for two vacuous greens.
      Verified against the pre-fix code with the other two new columns temporarily removed so
      nothing masked the assertion: `expected: 2026-01-01T10:00:00Z but was: 2026-01-01T05:00:00Z`
      - the predicted 5-hour shift, reproduced end to end.
      Note the test populates every other column deliberately: with `reward_points` or `aliases`
      left at their defaults, the statement fails on those first and the timestamp assertion is
      never reached.
- [x] 1.4 Integration test: a two-row batch update with a nullable non-text column null in every
      row succeeds (regression for SQLSTATE 42804). Added `reward_points` (nullable `INTEGER`) to
      the `dummies` fixture on all three dialects; test is
      `should_updateAll_when_a_nullable_column_is_null_in_every_row` in the shared
      `BaseRepositoryTest`. Passing on all three; fails against the pre-fix code.
- [x] 1.5 Integration test: a converter-backed column survives the batch path identically to the
      single-row path. Added `aliases` (`JSONB` on Postgres, `JSON` on MySQL/MariaDB) wired to the
      framework's `JSONBArrayNodeConverter` / `JSONArrayNodeConverter`, mapped to the domain's
      existing `List<String> aliases` by the new `Aliases` helper. Test is
      `should_preserve_converted_values_when_updateAll_batches_multiple_rows`. Passing on all
      three; fails against the pre-fix code.
- [x] 1.8 Regenerate jOOQ classes and run the three repository modules. Done - 85 tests pass on
      each of PostgreSQL, MySQL and MariaDB. Two things surfaced:
      - **MariaDB `JSON` is an alias for `LONGTEXT`**, so the generator reported `aliases` as
        `CLOB`/`String` and a `JSON`-typed converter could not attach. Resolved in the codegen
        config with `withName("JSON")` plus a column-expression match - no framework change; see
        1.9. The first attempt added a dialect-specific converter, which turned out to be
        unnecessary and was deleted.
      - The Postgres codegen carried a placeholder JSONB forced type pointing at
        `com.google.gson.JsonElement` / `com.example.PostgresJSONGsonBinding`, neither of which
        exists in this repo. It was dead until a JSONB column appeared; replaced with
        `JSONBArrayNodeConverter`.
- [x] 1.9 Resolve the MariaDB JSON converter story. Resolved with **no framework change**: a
      `mariadb.JSONArrayNodeConverter` was added while closing 1.8 and has since been deleted as
      unnecessary. The shared `JSONArrayNodeConverter` works on MariaDB once the forced type also
      carries `withName("JSON")`, which rewrites the generated `DataType` from `CLOB` back to
      `JSON` so a `Converter<JSON, ..>` can attach. Verified by codegen plus a full MariaDB run.
- [x] 1.10 Correct the MariaDB JSON codegen guidance. It was wrong in **9 files**, not one, and
      both alternatives it offered were tested against a real MariaDB container:
      - `(?i:JSON)` never matches - the generator reports the column as `CLOB`, so the forced type
        is skipped and the column generates as `String`.
      - `(?i:JSON|LONGTEXT)` matches but does not compile:
        `inference variable T#13 has incompatible equality constraints JSON,String`.
      Replaced everywhere with the verified recipe: `withName("JSON")` to rewrite the generated
      `DataType`, plus `withIncludeExpression` naming the column, bound to the **shared**
      converter. Corrected in `AGENTS.md` (prose, Gradle block, and a fourth claim at the old
      line 432 that asserted the opposite - "MariaDB JDBC reports JSON columns as JSON"),
      `docs/database/{multi-database,mariadb,jooq-codegen}.md`,
      `docs/{gradle,maven}/jooq-codegen.md`, and both website mirrors
      (`reference/multi-database.mdx`, `learn/complete-project-setup.mdx`).
      Each file's MariaDB and MySQL blocks were identified before editing so the MySQL guidance -
      which is correct, and verified by a passing MySQL module - was left untouched.
      Every affected section now also separates hand-declared fields (`EventEntityRepository`
      uses `SQLDataType.JSON.asConvertedDataType(...)`, telling jOOQ the type instead of asking
      the database, so MariaDB never bites) from code generation, where the reported type wins.

## 2. Transaction cleanup safety (design.md finding 2)

- [ ] 2.1 `Transaction.rollback()`: move `setAutoCommit(initialAutoCommit)` out of the `finally`
      so it runs only after `connection.rollback()` returns normally.
- [ ] 2.2 Apply the same ordering to `Transaction.commit()`.
- [ ] 2.3 Update the comment at lines 59-67 - it currently describes a guard the `finally`
      pre-empted.
- [ ] 2.4 Unit test (Mockito): a `rollback()` that throws never calls `setAutoCommit`, and marks
      the transaction dirty.
- [ ] 2.5 Unit test: the happy path still restores autocommit and does not mark dirty.

## 3. Quarkus config binding parity (design.md finding 4)

- [ ] 3.1 Stop using enumerated names as Jackson keys in `EkbatanCoreConfiguration.java:73-79`
      and in the shared `bindSubtree` helper at `:140-146`. Either filter SmallRye's
      `toLowerCaseAndDotted` syntheses or invert the loop and look up canonical keys directly.
- [ ] 3.2 Replace `config.getOptionalValue(name, String.class).ifPresent(...)` with
      `config.getConfigValue(name)` + null check, so `""` survives. Both loops.
- [ ] 3.3 Align the `@IfBuildProperty` gates with the key spellings the binder accepts.
- [ ] 3.4 Test in all three DI suites: an empty password binds as `""`.
- [ ] 3.5 Test in the Quarkus suite: an `EKBATAN_SHARDING_..._PASSWORD` environment variable binds
      correctly and no synthesized alias reaches the strict mapper.
- [ ] 3.6 Test: the camelCase enable flag actually starts the local-event-handler job.

## 4. Event dispatch fault isolation (design.md findings 3 and 6)

- [ ] 4.1 `EventHandlingJob.classify()`: catch `Throwable` rather than `Exception` (rethrowing
      `VirtualMachineError` if preferred), recording the notification as `FAILED` with its cause.
- [ ] 4.2 Make the four bucket writes at lines 236-246 robust to an abnormal fork, so decided
      outcomes still commit.
- [ ] 4.3 Test: a handler throwing `NoClassDefFoundError` marks only its own row `FAILED`, leaves
      siblings correctly bucketed, and advances `next_retry_at` so the next poll makes progress.
- [ ] 4.4 `EventFanoutJob`: derive the round-progress signal and both metrics from rows written on
      primary, not `events.size()` read from the replica.
- [ ] 4.5 Add `.and(DELIVERED.eq(false))` to `markDelivered` so its update count is meaningful.
- [ ] 4.6 Test: a round that transitions nothing reports no progress and the loop sleeps for
      `pollDelay`.

## 5. Keyed lock resource accounting (design.md findings 5 and 6)

- [ ] 5.1 `InProcessKeyedLockProvider.tryAcquire`: single try/finally releasing the entry on every
      non-success exit - timeout, interrupt, and any `RuntimeException` from `register()`.
- [ ] 5.2 `KeyedReentrantHolder` watchdog: wrap the release callback in its own
      `catch (RuntimeException | Error)` logging at ERROR, and move (or reword) the "auto-released"
      `LOG.warn` so it never claims a release that has not happened.
- [ ] 5.3 `RedisKeyedLockProvider.backendRelease`: unwrap `CompletionException` and branch -
      `IllegalMonitorStateException` stays DEBUG, everything else WARN/ERROR with the cause.
- [ ] 5.4 `RedisKeyedLockProvider`: reject or round up a positive sub-millisecond `maxHold` so it
      can never become `leaseTime = 0`.
- [ ] 5.5 `MySQLKeyedLockProvider:106`: wrap `connectionProvider.acquire()` failures in
      `LockAcquisitionException`. Check the sibling providers for the same gap.
- [ ] 5.6 `PostgresKeyedLockProvider:77`: do not let `Thread.interrupted()` strip the flag from
      the reentry path.
- [ ] 5.7 Tests: interrupt-flag-already-set `tryAcquire` leaves `activeKeyCount()` at zero; a
      throwing release callback keeps the watchdog thread alive and logs at ERROR; a Redis release
      failure is not logged as "no longer held".

## 6. Connection pool lifecycle (design.md finding 6)

- [ ] 6.1 `DatabaseRegistry.java:153-155`: fall back on the *provider*, not the config -
      `member.secondaryConfig().map(ConnectionProvider::hikariConnectionProvider).orElse(primaryProvider)`.
      `TransactionManager.close()`'s identity guard then does the right thing.
- [ ] 6.2 `DatabaseRegistry.java:167`: close already-started pools when construction fails
      partway, including on a duplicate `(group, member)`.
- [ ] 6.3 Test: a no-replica member produces exactly one `HikariDataSource`, and primary and
      secondary providers are the same instance.
- [ ] 6.4 Verify the behaviour matches `docs/database/sharding.md:279` and the
      `DatabaseRegistry.java:46` javadoc; correct whichever is wrong.

## 7. Remaining confirmed defects (design.md finding 6)

- [ ] 7.1 `Retry.java:36`: per-exception-type attempt counters rather than one global counter.
- [ ] 7.2 `AutoBuilderProcessor`: emit a `Messager` error for a non-direct `Model`/`Entity`
      subclass instead of crashing javac (`:106`); carry type variables for generic domain classes
      (`:110`); add a getter name-collision check (`:150`).
- [ ] 7.3 `Jackson3RecordsFeature.java:266`: add the `NoClassDefFoundError` guard its three
      sibling Features document as mandatory.
- [ ] 7.4 `FlywayMigrator.java:162`: capture `locations` after the user customizer runs, honoring
      the documented ordering.
- [ ] 7.5 `EkbatanActionsHolder.java:26`: scope or reset the process-global static so one context's
      AOT action list cannot leak into another.
- [ ] 7.6 `DataSourceConfig.java:56`: match `mariadb` before `mysql`, and anchor the match to the
      JDBC subprotocol rather than searching the whole URL.
- [ ] 7.7 `SingleTableJsonEventPersister.java:101`: validate the payload as the sibling
      `actionParams` path does instead of blind-casting to `ObjectNode`.

## 8. Builder validation cluster (design.md finding 7)

Per `AGENTS.md`, all validation belongs in the target constructor even when the builder has a
default.

- [ ] 8.1 `DataSourceConfig`: validate `maximumPoolSize` positive and `leakDetectionThreshold`
      non-negative.
- [ ] 8.2 `ShardGroupConfig` / `ShardMemberConfig`: enforce the ranges `ShardIdentifier` mandates
      (group 0..255, member 0..63).
- [ ] 8.3 `RetryConfig`: reject a negative delay.
- [ ] 8.4 `EventNotification`: bound `attempts`.
- [ ] 8.5 Replace `Optional.of` in setters with a `Validate` call so an explicit null yields a
      named message rather than a bare NPE.

## 9. Close the disclosed audit gaps (design.md "Not audited")

- [ ] 9.1 Audit `ekbatan-distributed-jobs` - `JobRegistry`, `DistributedJob`, `JobsConfig`.
- [ ] 9.2 Audit the Debezium SMT transforms and the `action-event` wire POJOs under
      `ekbatan-events/streaming/`.

## 10. Verification

- [ ] 10.1 Full `./gradlew check` including the Testcontainers integration modules on all three
      dialects.
- [ ] 10.2 Re-run the audit's lenses over the changed files to confirm no new defect was
      introduced, and update `design.md` with anything the re-run finds.
