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

- [x] 2.0 Verify the finding before fixing it. Every load-bearing claim was checked at source and
      then reproduced against live databases:
      - HikariCP never has auto-commit disabled by the framework - `hikariConnectionProvider` does
        not touch it, and `HikariConfig:127` defaults `isAutoCommit = true`. So the reset is a real
        `false -> true` transition, not a no-op.
      - `ProxyConnection.setAutoCommit` (HikariCP 7.0.2, line 412) passes straight to the driver.
      - The flip is a commit on **all three** dialects, not just MySQL/MariaDB:
        pgjdbc `PgConnection.setAutoCommit` calls `commit()` outright (line 962); MySQL
        Connector/J sends `SET autocommit=1`; the MariaDB driver sends `set autocommit=1`.
      - Empirically, on PostgreSQL 18, MariaDB 11 and MySQL 8: an `INSERT` followed only by
        `setAutoCommit(true)` - no `commit()` call anywhere - leaves the row **committed**, while
        the same transaction followed by a physical `close()` leaves **zero rows**. The defect and
        the fix's safety net were both confirmed on every dialect.
      - `ConnectionProvider.evict` -> `HikariPool.evictConnection` -> `softEvictConnection(owner=true)`
        -> `closeConnection` -> physical close, bypassing `ProxyConnection.close()`. So eviction
        really does let the server discard the open transaction.
- [x] 2.1 `Transaction.rollback()`: `setAutoCommit(initialAutoCommit)` moved out of the `finally`
      so it runs only after `connection.rollback()` returns normally.
- [x] 2.2 Same ordering applied to `Transaction.commit()`. This one matters more than first
      assessed: a failed commit previously flipped auto-commit (committing the data the caller was
      told had failed), after which the follow-up `rollback()` found nothing to roll back,
      succeeded, and the connection was **released to the pool** rather than evicted.
- [x] 2.3 Comment rewritten - it described a guard the `finally` pre-empted.
- [x] 2.4 Unit tests added to `TransactionManagerTest`:
      `does_not_restore_autocommit_when_rollback_fails`,
      `does_not_restore_autocommit_between_a_failed_commit_and_its_rollback` (asserts exactly one
      restore, ordered after the rollback - the call *count* is what separates fixed from broken).
      Both **fail against the pre-fix code**, verified by reverting.
- [x] 2.5 `restores_autocommit_after_a_successful_rollback` pins the happy path with an ordered
      `setAutoCommit(false)` -> `rollback()` -> `setAutoCommit(true)` and no eviction.
- [x] 2.6 Correct the inaccurate Hikari rationale. `ProxyConnection.close()` rolls back (line 250)
      BEFORE resetting auto-commit (line 255), and both sit in one try block, so a failed rollback
      skips the restore - Hikari's return path could never have committed anything. Fixed in the
      stale comment in `TransactionManagerTest`, and `docs/database/transaction-manager.md` +
      `website/src/pages/reference/transaction-manager.mdx` now document the actual rule.
- [x] 2.7 Deep-research review of the applied fix (11 agents, every lens independently re-checked,
      end-to-end harnesses on live databases). Verdict: **KEEP** - no change to the logic. What it
      established:
      - **The defect and the fix reproduce on all three dialects**, twice each with independently
        built harnesses: pre-fix the row survives a failed rollback (1 row), post-fix it does not
        (0 rows). The MySQL/MariaDB general query log shows `SET autocommit=0` -> `INSERT` ->
        `SET autocommit=1` with no COMMIT and no ROLLBACK, and the row durable.
      - **The fix matches HikariCP's own discipline exactly.** `ProxyConnection.close()` rolls back
        first and resets auto-commit second, in one try block. More: by setting `isAutoCommit=true`
        the old `finally` falsified Hikari's `if (isCommitStateDirty && !isAutoCommit)` guard, so it
        **disarmed Hikari's own rollback-on-return safety net in the same statement that committed
        the transaction**. The fix re-arms it. Demonstrated by driving a real HikariDataSource over
        a recording JDBC fake: OLD+release -> no retry; NEW+release -> rollback retried.
      - **No regression on any path**, and no better alternative. Notably, deleting the restore and
        letting Hikari do it (the simpler-looking option) is worse: the reset then happens inside
        `ProxyConnection.close()`, where a failure escapes `release()` and is thrown from
        `TransactionManager`'s finally, masking the caller's original business exception.
      - The fix also removes a spurious-eviction bug on the failed-commit path: pre-fix, pgjdbc
        rejects `rollback()` when auto-commit is enabled, so every failed commit (deadlock,
        serialization failure) forced a physical reconnect.
- [ ] 2.8 Follow-up, out of scope here (pre-existing, unaffected by this change):
      `TransactionManager.java:174` catches `Exception`, not `Throwable`. An `Error` from the action
      block releases a connection with `autoCommit=false` and an open transaction rather than
      evicting it. Proven by probe against the fixed code. Related to finding 3's `classify()` gap.
- [ ] 2.9 Follow-up, out of scope here: a pgjdbc lazy-`BEGIN` hazard found with `log_statement=all` -
      a cancelled `BEGIN` lets the subsequent INSERT auto-commit outside any transaction while the
      caller sees an exception. Present identically before and after this change.

## 3. Quarkus config binding parity (design.md finding 4)

- [x] 3.0 Reproduce before fixing. A throwaway harness registered a real
      `EnvConfigSource` in-process and printed what SmallRye actually does. Two corrections to the
      audit's account, both material:
      - **The env-var spelling in the audit was wrong.** SmallRye maps `].` in a property name to a
        **double** underscore. `EKBATAN_SHARDING_GROUPS_0__MEMBERS_0__CONFIGS_PRIMARYCONFIG_PASSWORD`
        resolves `...groups[0].members[0]...`; the single-underscore form the audit quoted is a
        different property and its alias is `groups[0]members[0]configs...` with the dots missing.
      - **The failure is not an unknown-property rejection.** With the correct spelling the alias is
        structurally right and only the map key's casing is wrong (`primaryconfig`). Since `configs`
        is a `Map<String, DataSourceConfig>`, that is accepted as a *new map key*, producing a
        phantom entry with no `jdbcUrl` - so the bind dies with `jdbcUrl is required`.
- [x] 3.1 Both copy loops replaced by a shared `collectSubtree` collector. It no longer uses an
      enumerated name as a Jackson key: names published by an `EnvConfigSource` have their casing
      restored against the canonical spellings that case-preserving sources supply, longest-prefix
      first.
      Note the pure "invert the loop and look up canonical keys" option was **discarded after
      testing**: `ShardMemberConfig.configs` is a `Map<String, DataSourceConfig>` whose keys are user
      data, so the expected key set cannot be derived from the class. A first implementation that
      resolved values by canonical name also broke every kebab-case test, because SmallRye only
      resolves names as written - values are now read back under the source's own name, with
      duplicates settled by source ordinal so an env var still overrides a file.
- [x] 3.2 `getOptionalValue` replaced by `getConfigValue(name).getValue()`, so `""` survives. Both
      loops, via the shared collector.
- [x] 3.4 / 3.5 Nine `EnvironmentVariables` tests and three `EmptyValues` tests added to
      `EkbatanCoreConfigurationTest`, built on a **real `EnvConfigSource`** rather than hand-written
      aliases - the alias format is SmallRye's behaviour, not ours, and an imitation would stop
      catching regressions if it changed. Fixture ordinals are 250/300 to mirror Quarkus's real
      `application.properties` and environment precedence. **6 of the new tests fail against the
      pre-fix binder**, verified by stashing it.
      Coverage: value supplied only by env; no phantom lower-cased entry; env overrides file;
      casing repaired from a kebab-spelled map key; unrelated env vars ignored; no env source at
      all; empty password from file and from env; and empty still distinguished from absent.
- [x] 3.5a Two boundaries pinned as tests rather than left as surprises: the single-underscore env
      spelling is a *different property* and must fail loudly; and a camelCase leaf supplied **only**
      by an env var (`EKBATAN_JOBS_POLLING_INTERVAL` -> alias `ekbatan.jobs.polling.interval`, but
      the property is `pollingInterval`) cannot have its casing restored, because no case-preserving
      source offers a spelling to restore from. Documented limitation: topology in a file plus
      secrets in the environment works; configuring a subtree entirely from the environment does not.
- [x] 3.3 Align the Quarkus `@IfBuildProperty` gate with the key spellings the binder accepts.
      **Verified Quarkus-only** - both other integrations were probed empirically rather than assumed:
      - **Spring: immune.** With `ekbatan.localEventHandler.handling.enabled=true`, an
        `ApplicationContextRunner` slice still produced the `EventHandlingJob` bean. Spring Boot's
        relaxed binding canonicalises both spellings to the same `ConfigurationPropertyName`.
      - **Micronaut: immune.** With a camelCase source key, `env.getProperty(...)` under the
        *kebab* name used by `@Requires` returned `Optional[true]`. Micronaut normalises property
        names, so the gate resolves either spelling.
      - **Quarkus: broken.** `@IfBuildProperty(name = "...")` matches the name as a literal string
        and only knows the kebab form, while `bindSubtree` accepts both - so the config reports
        `handling.enabled=true` while the bean is never produced. Silent no-op; notifications
        accumulate undelivered.
      **Fixed, in two layers.**
      1. `EkbatanPropertyNameInterceptor` - a SmallRye `ConfigSourceInterceptor` in the extension's
         **runtime** jar (not deployment - it must be on the augmentation classloader), registered
         via `META-INF/services/io.smallrye.config.ConfigSourceInterceptor`. When an `ekbatan.*`
         lookup misses, it retries the other spelling and returns the hit under the caller's own
         name. This fixes the whole class of problem rather than this one property: every
         name-based consumer - `@IfBuildProperty`, `@UnlessBuildProperty`, `@LookupIfProperty`,
         `@ConfigProperty`, `getOptionalValue` - now accepts both spellings for free.
         Deliberately: name-as-written always wins, so it can never change the meaning of an
         explicitly-set property; only `ekbatan.` keys are touched; `iterateNames` is left as
         pass-through so no synthetic names leak into Quarkus's config validation; and `proceed()`
         rather than `restart()` makes recursion impossible.
         **Every** spelling is covered, not only the two extremes. A key with N hyphens has 2^N
         accepted spellings (`local-event-handler`, `localEventHandler`, `local-eventHandler`,
         `localEvent-handler`), so rather than enumerate candidates - exponential, and still
         incomplete - the interceptor folds each name to its fully-camelCase form and matches on
         that: after a cheap direct retry it scans `iterateNames()` for the one name whose canonical
         form equals the lookup's. The scan runs only when an `ekbatan.` lookup has already missed.
         This made the code smaller, not bigger - the previous `camelToKebab` helper became
         unreachable and was deleted.
      2. `verifyHandlingJobGate` - a `@Observes StartupEvent` check in
         `EkbatanLocalEventHandlerConfiguration` that throws when the bound config says handling is
         enabled but no `EventHandlingJob` bean exists. With all spellings now resolving it is a
         backstop rather than a necessity, and it still earns its place: it catches any future
         gate/binder divergence, and a property visible to the runtime but not during augmentation.
         One-directional on purpose - it does not complain when config says disabled but a bean
         exists, since an app may produce its own.
      **Verified by A/B on the real end-to-end Quarkus test**, with the example app's
      `application.properties` switched to the camelCase spelling as the only gate key:
      - service file present -> 2 tests pass, the gate fires;
      - service file removed -> `Failed to start quarkus`, which is layer 2 catching it.
      One experiment, both layers proven. Repeated afterwards with a **mixed** spelling
      (`ekbatan.local-eventHandler.handling.enabled`) as the only gate key - also 2 tests, 0
      failures. Config restored to kebab each time.
      Prototyping also built a **real GraalVM native image** with the camelCase key as the only gate
      key and `testNative` passed - no extra native configuration needed, because Quarkus's
      `ConfigBuildSteps.nativeServiceProviders` already registers the `ConfigSourceInterceptor` SPI.
      Two methodology notes for anyone re-running this: each gate scenario needs its own Gradle
      invocation (Quarkus reuses augmentation across `@TestProfile` classes, so several in one JVM
      report a leaked build), and concurrent edits to the same tree contaminate the results.
- [x] 3.6 Test: the camelCase enable flag actually starts the local-event-handler job. Seven
      in-process `EkbatanPropertyNameInterceptorTest` cases pin the mechanism the gate relies on -
      kebab lookup finds a camel source and vice versa, absent stays absent, an explicit spelling is
      never overridden by an alias, non-`ekbatan.` keys are untouched, and the interceptor is
      discovered by `ServiceLoader`. Five more cover the mixed spellings in both directions, plus a
      guard that the canonical fold is not too eager - two genuinely different keys must not be
      matched to each other. Twelve in total. The end-to-end proof is the A/B recorded under 3.3.

## 4. Event dispatch fault isolation (design.md findings 3 and 6)

- [x] 4.0 Research the convention before writing the fix (7 agents, every survey re-checked). Two
      findings changed the design:
      - **`catch (Throwable)` at a user-handler boundary is the platform norm**, not an oddity.
        `FutureTask.run()`, `ForkJoinTask`, `StructuredTaskScope`, Quartz, Spring `@Scheduled`,
        Netty, Micronaut, Vert.x, Tomcat and JUnit all do it. Decisively, **db-scheduler - the
        frame that calls `EventHandlingJob.execute()` - does it too**: `ExecutePicked.java:118-123`
        has `catch (RuntimeException)` then `catch (Throwable unhandledError)`, both routing to the
        same failure path, with no fatal carve-out anywhere in the library. It even documents the
        reasoning in the same words, at `Scheduler.java:458`:
        `catch (Throwable ex) // just-in-case to avoid any "poison-pills"`.
      - **The mainstream "fatal throwable" sets could not be used here.** Reactor's
        `Exceptions.isJvmFatal` is `VirtualMachineError || ThreadDeath || LinkageError`; RxJava and
        Scala's `NonFatal` match. `NoClassDefFoundError` IS a `LinkageError`, so adopting any of
        them verbatim would rethrow the exact case this fix exists to absorb. An earlier draft
        rethrew `OutOfMemoryError` only; that was dropped as both bespoke and inert - db-scheduler
        catches `Throwable` above us and reschedules regardless, so the carve-out could not fail
        fast, only sideways.
- [x] 4.1 `classify()` left catching `Exception` only. A `catch (Throwable)` arm was written and
      then dropped: with 4.2 in place it is semantically inert - the `Error` escapes the fork,
      `FutureTask` stores it, and the collection loop records the same `FAILED` for the same row,
      with the same `attempts+1` and the same backoff. It bought a better log line at the cost of a
      ten-line comment defending a decision with no consequences.
- [x] 4.2 The collection loop no longer rethrows on `ExecutionException`: a fork that died without
      producing an `Outcome` is recorded as `FAILED` for its own row, so the batch always reaches
      its state UPDATEs. This is the load-bearing half - the rethrow, not the narrow catch, is what
      turned one bad row into a discarded batch. Severity was worse than first assessed: the
      sibling handlers had **already run successfully**, so their success was never recorded and
      they were re-invoked once per poll forever - at-least-once silently becoming
      at-once-per-second-forever.
- [x] 4.2b Meters improved on the handling job (separate from the defect fix):
      - `HANDLED` gained a `handler` dimension alongside `outcome`, on all four outcomes. Counters
        are grouped per handler before emitting, so a 100-row batch costs one add per distinct
        handler rather than one per row. Totals are unchanged - summing across the new dimension
        reproduces exactly the numbers emitted before.
      - New `ekbatan.events.handler.duration` histogram (seconds), tagged by handler and a binary
        `succeeded`/`failed` outcome. Timed with `System.nanoTime()`, not the injected `Clock` -
        elapsed time, and the `Clock` is a `VirtualClock` under test.
      - New `ekbatan.events.delivery.lag` histogram (seconds), tagged by handler: how old the source
        event was when its handler finally succeeded. Recorded on success only, so it measures
        time-to-delivery rather than time-spent-failing. This is the instrument that reveals a
        growing backlog, which no counter can - a shard falling behind still reports healthy
        success counts.
      - The four outcome bucket lists now carry `EventNotification`s rather than bare UUIDs, since
        both the handler name and the event date live on the row. Repository calls take
        `idsOf(bucket)`.
      Note the deliberate vocabulary difference: `handler.duration`'s `outcome` is binary, because
      at the moment a handler returns the only known fact is whether it threw - retry-vs-expiry is
      decided later, and a pre-flight expiry never invokes a handler at all.
- [x] 4.2c Documented the metrics. `docs/runtime/observability.md` was titled "OpenTelemetry
      tracing" and covered only spans - every instrument in the framework was undocumented. Added a
      Metrics section listing all five instruments with type, unit, tags and emitting job, what each
      one answers, the four `outcome` values, and why `handler.duration`'s `outcome` is deliberately
      binary. Mirrored verbatim in `website/src/pages/reference/runtime/observability.mdx`; both
      retitled to "OpenTelemetry tracing and metrics". Verified every documented name, tag value and
      unit against the code, and that no instrument in the code is missing from the docs.
      Also fixed pre-existing drift found in passing: the website mirror documented
      `db.operation.name` as `"INSERT"`/`"UPDATE"`, but the code emits `BATCH_INSERT`/`BATCH_UPDATE`
      (as `docs/` correctly said).
      Noted the known caveat in both: `ekbatan.events.fanned_out` counts rows read from the replica,
      not rows written, so it over-reports under replication lag (see 4.4).
- [ ] 4.2a Alerting on handler `Error`s is still an open question, deliberately. A dedicated
      `ekbatan.events.handler.errors` counter was added and then removed: the job already exposes a
      single `HANDLED` counter tagged by outcome, and bolting a second counter onto one case breaks
      that symmetry. If handler-level visibility is wanted, it should be a consistent dimension
      across every outcome, not a special case for this one. Worth noting the concern that motivated
      it, so it is not lost: a `NoClassDefFoundError` is a deployment fault retrying cannot fix, so
      the row now retries and eventually EXPIRES past the retention window - the framework discards
      a business event because a JAR was missing, where previously the shard stopped and someone
      noticed. That trade (silent loss vs loud stall) is the strongest argument against 4.2 and is
      currently unmitigated.
- [x] 4.3 Test `an_error_from_one_handler_does_not_discard_the_rest_of_its_batch` in the shared
      `BaseLocalEventHandlerIntegrationTest`, so PostgreSQL, MySQL and MariaDB all inherit it. Two
      handlers subscribe to one event so both notifications land in one batch; one throws
      `NoClassDefFoundError`, the other succeeds. Asserts the healthy sibling reaches `SUCCEEDED`,
      the poisoned row reaches `FAILED`, and the next poll does not re-deliver the succeeded one.
      Verified against the pre-fix code: fails with `java.lang.RuntimeException: Handling worker
      threw`. Passing on all three dialects (10 tests each).
      The fixture handler throws `NoClassDefFoundError` specifically, not a generic `Error`,
      because it is a `LinkageError` - the subtype every mainstream fatal set rethrows. That keeps
      any future "let's use throwIfFatal" refactor honest.
- [ ] 4.4 `EventFanoutJob`: derive the round-progress signal and both metrics from rows written on
      primary, not `events.size()` read from the replica.
- [ ] 4.5 Add `.and(DELIVERED.eq(false))` to `markDelivered` so its update count is meaningful.
- [ ] 4.6 Test: a round that transitions nothing reports no progress and the loop sleeps for
      `pollDelay`.

## 5. Keyed lock resource accounting (design.md findings 5 and 6)

- [x] 5.1 `InProcessKeyedLockProvider` **deleted** rather than fixed - the refcount leak goes with
      it. Rationale in `design.md` finding 5. Removed: the class, its reentrancy tests, the provider
      row and FIFO claims in `docs/database/keyed-locks.md` + the website mirror, the entry in
      `AGENTS.md`, and the javadoc reference in `KeyedLockProvider`.
      `KeyedReentrantHolder` stays - the other four providers depend on it.
      **Breaking change**: the class has been published since v0.2.1, so it belongs in the release
      notes. Also note the docs no longer advertise any FIFO-fair provider; the fairness paragraph
      now says ordering is best-effort across the board and points at optimistic locking.
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
