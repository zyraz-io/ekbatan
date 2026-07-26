# Audit findings — codebase read-through

Recorded 2026-07-25. Findings from a full read of `ekbatan-core`, `ekbatan-di`,
`ekbatan-distributed-jobs`, all six `KeyedLockProvider` implementations,
`ekbatan-events` (local-event-handler + streaming SMTs), `EventEntity` +
core `EventEntityRepository`, all 44 files under `docs/`, and part of `website/`.

**Scope note.** Almost nothing here is part of the
`framework-resilience-and-event-ordering` change itself — this is a parked backlog
that happened to surface while reading for it.

**Status as of 2026-07-26: 20 addressed, 0 open. This backlog is closed.** All of section A (documentation
contradicting code) is fixed. Items 13, 14 and 16 are fixed; 18 is a note rather than a
defect; 19 was superseded by the group 2 rescope. Every finding is closed: 18 fixed, 1 superseded by the group 2 rescope (19), 1 resolved as a keep decision (17), and 1 recorded as a non-defect note (18).

Every item marked **verified** was confirmed against the source (file:line given).
Items marked **reasoned** were derived from reading the code but not executed.

---

## A. Documentation contradicts code (highest value — these mislead users)

### [x] 1. `TransactionManager` docs are wrong throughout; every example is uncompilable — **FIXED 2026-07-26**

> docs + website `transaction-manager` rewritten: `DSLContext` block parameter, `Transaction` noted as internal, `registry.primary`/`secondary` maps instead of the non-existent accessors, and the nesting claim corrected (ScopedValue shadows rather than throws).

**Where:** `docs/database/transaction-manager.md` and
`website/src/pages/reference/transaction-manager.mdx` (same content, both copies).

| Doc claims | Actual | Evidence |
|---|---|---|
| `inTransaction(Function<Transaction, R>)` and the three sibling overloads | `Function<DSLContext, R>` / `Consumer<DSLContext>` / `CheckedFunction<DSLContext, R>` / `CheckedConsumer<DSLContext>` | `TransactionManager.java:104,122,137,151` |
| `transaction.dslContext()`, `transaction.connection()` | `Transaction` is **package-private** (`class Transaction`) — not reachable from user code at all. The lambda parameter already *is* the `DSLContext`. | `Transaction.java:11` |
| `registry.readonlyDb(shard)`, `registry.primaryDb(shard)` | Neither method exists. `DatabaseRegistry` exposes public `Map<ShardIdentifier, DSLContext>` fields `primary` / `secondary`. | `DatabaseRegistry.java:44,47` + full public method list |

Status: **verified.**

Also on the same page: *"Calling `tm.inTransaction(...)` from inside an already-open
`inTransaction(...)` on the same `tm` will throw."* — `executeInTransaction` uses
`ScopedValue.where(...).call(...)`, which **shadows** an existing binding rather than
rejecting it, so a nested call would quietly open a second transaction on a second
pooled connection. Status: **reasoned** — worth an actual test before rewriting the
paragraph, since the right fix might be to *make* it throw rather than to document
the current behaviour.

### [x] 2. `db.operation.name` attribute values are wrong — **FIXED 2026-07-26**

> `AGENTS.md` and `observability.md` now document `BATCH_INSERT` / `BATCH_UPDATE`, with a note that the key follows OTel convention but the values are Ekbatan-specific.

**Where:** `AGENTS.md:514` and `docs/runtime/observability.md:33` both document
`"INSERT"` / `"UPDATE"`.
**Actual:** `"BATCH_INSERT"` / `"BATCH_UPDATE"` — `AbstractRepository.java:404,550`.

Consequence: any span query or dashboard filter written from the docs matches nothing.
Decide which side moves — the code value is arguably the more accurate one, but
`INSERT`/`UPDATE` is closer to the OTel semantic convention the doc cites.

Status: **verified.**

### [x] 3. `persistEvents` does not exist — **FIXED 2026-07-26**

> `action-executor.mdx` now calls `persistActionEvents(...)` with all 8 arguments.

**Where:** `website/src/pages/reference/action-executor.mdx:103`

```java
executor.eventPersister.persistEvents(namespace, sourceAction, params, startedAt, events, shard, actionId);
```

**Actual:** `EventPersister.persistActionEvents(...)` — different name, and 8 parameters
(the snippet omits `completionDate`). `EventPersister.java:40`.

Status: **verified.**

### [x] 4. "Five required columns" overstated in all three per-dialect docs — **FIXED 2026-07-26**

> All three dialect docs now state the real split: `AbstractRepository` requires `version` + `state`; `ModelRepository` adds the timestamps; `Entity` tables need neither.

**Where:** `docs/database/postgresql.md`, `mariadb.md`, `mysql.md` — each says
*"`AbstractRepository` requires every domain table to carry five columns. Startup fails
if any is missing"* and lists `id`, `version`, `state`, `created_date`, `updated_date`.

**Actual:** `AbstractRepository` requires **two** (`version`, `state` —
`AbstractRepository.java:130,134`). `ModelRepository` adds `created_date` /
`updated_date` (`ModelRepository.java:48,51,73,76`). An `Entity` table needs neither.

This also self-contradicts `docs/concepts/models-and-entities.md`, which correctly
states Entities have no framework-managed timestamps.

Status: **verified.**

### [x] 5. AGENTS.md forbids what docs/ and the core javadoc allow (threads in `perform()`) — **FIXED 2026-07-26**

> `AGENTS.md` section rewritten as 'Single-Writer Action Execution' — threads allowed but not encouraged, with the two hard rules (`plan()` only on the invoking thread, never share the transactional `Connection`) and the fan-out/join example. `docs/concepts/actions.md` heading softened to match.

- `AGENTS.md` §"Single-Threaded Action Execution": *"Do not spawn concurrent threads
  inside `Action.perform()`."*
- `docs/concepts/actions.md` §"Plan is single-writer; spawning threads is fine":
  *"Spawning parallel threads inside `Action.perform()` is **allowed**."*
- `Action.java` javadoc sides with `docs/` ("If `perform()` spawns parallel work...").

Both agree on the real constraint — only the invoking thread may touch `plan()` or the
transactional connection. AGENTS.md is the outlier and overstates the prohibition.

Status: **verified.**

### [x] 6. AGENTS.md references a `buildSrc/` directory that does not exist — **FIXED 2026-07-26**

> Both references now say `build-logic/`.

**Where:** AGENTS.md module tree (~line 58) and §"Build & Tooling" (~line 962).
**Actual:** the repo uses `build-logic/`. There is no `buildSrc/`.

Status: **verified.**

### [x] 7. Published-module count is off by one — **FIXED 2026-07-26**

> Now says 17.

**Where:** `AGENTS.md:1023` — *"16 modules are published."*
**Actual:** 17 modules apply the `ekbatan.publishing` convention plugin. (An 18th grep
hit, `build-logic/build.gradle.kts`, only mentions it in a comment.) `README.md` already
says 17, so AGENTS.md is the stale one.

Status: **verified.**

### [x] 8. Broken internal doc link — **FIXED 2026-07-26**

> Links repointed to the per-stack wiring guides. Re-checked every relative link in `docs/` — all resolve.

`docs/wiring/without-di.md` links to `with-di.md` three times; no such file exists.
It is the **only** broken relative link in `docs/` — all others check out.

Status: **verified** (checked every relative `.md` link in `docs/`).

### [x] 9. `reference/action.mdx` omits `throws Exception` — **FIXED 2026-07-26**

> `action.mdx` signature now carries `throws Exception`.

Declares `protected abstract R perform(Principal principal, P params);`
Actual: `protected abstract RESULT perform(Principal principal, PARAM params) throws Exception;`
(`Action.java:103`). Minor, but it is an API-signature reference page.

Status: **verified.**

### [x] 10. Cross-shard rollback wording is misleading — **FIXED 2026-07-26**

> `sharding.md` now says committed shards stay committed, mentions the CRITICAL partial-commit log, and explains the ordering is deterministic-but-incidental.

**Where:** `docs/concepts/sharding.md` — *"opens one transaction per shard, in
deterministic order, rolling each back independently on failure."*

Two nits: (a) already-committed shards are **not** rolled back — which the same page
says correctly elsewhere, but this sentence reads otherwise; (b) the order is
deterministic but incidental — it is `LinkedHashMap` insertion order from
`groupChangesByShard`, which walks entity classes and stages **all additions before all
updates**. Worth stating explicitly, since the partial-commit logging spec depends on it.

Status: **verified** (`ActionExecutor.groupEntityChangesByShard`).

---

## B. Javadoc drift in the lock providers

### [x] 11. `PostgresKeyedLockProvider` claims SipHash-2-4 — **FIXED 2026-07-26**

> Class javadoc now says SHA-256 truncated to its first 8 bytes, and links `LockKeyHash` as the
> single source of truth rather than restating the algorithm.

Class javadoc: *"Keys are hashed via SipHash-2-4 into Postgres's 64-bit advisory-lock
identifier."*
Actual: `LockKeyHash.hashUtf8` uses **SHA-256 truncated to its first 8 bytes**, and its
own javadoc says so explicitly.

Status: **verified.**

### [x] 12. `LockKeyHash` javadoc names the wrong Postgres function — **FIXED 2026-07-26**

> Now names the session-scoped `pg_advisory_lock` / `pg_try_advisory_lock`, and explicitly notes
> the transaction-scoped `pg_advisory_xact_lock` is *not* used because a lease deliberately
> outlives any transaction on the borrowed connection.

Says it feeds `pg_advisory_xact_lock(bigint)`. The provider calls session-scoped
`pg_advisory_lock` / `pg_try_advisory_lock` / `pg_advisory_unlock` — the transaction-scoped
variant is never used. The distinction matters: session scope is what makes
lease-outlives-transaction behaviour possible.

Status: **verified.**

### [x] 13. `MariaDBKeyedLockProvider` carries a stale copy-pasted MySQL comment — **FIXED 2026-07-26**

> Removed incidentally: the whole `acquire` body containing it was replaced by the segmented wait loop in group 2.

Inside `acquire`: *"GET_LOCK with a negative timeout normally waits forever."*
MariaDB passes `WAIT_FOREVER_SECONDS = Integer.MAX_VALUE` precisely **because** modern
MariaDB rejects negative timeouts — which the class-level javadoc explains correctly a
few lines above. The inline comment is the MySQL one.

Status: **verified.**

### [x] 14. Dangling javadoc link in `MariaDBKeyedLockProvider` — **FIXED 2026-07-26**

> Removed incidentally: the link lived in `WAIT_FOREVER_SECONDS`'s javadoc, and that constant was deleted in group 2.

`{@link #acquire(Object, Duration)}` — the method is `acquire(String, Duration)`.
`-Xdoclint` would flag this.

Status: **verified.**

---

## C. Code-level issues

### [x] 15. Core `EventEntityRepository` is not shard-aware for dialect resolution — **FIXED 2026-07-26**

> Rewritten to match the two `local-event-handler` repositories, which were already correct:
> dialect-specific fields resolved via `fieldsFor(shard)` per call, dialect-neutral columns as
> shared constants, and `txDbElseDb(shard)` falling back to **that shard's** primary instead of the
> default shard's cached `DSLContext`.
>
> Closed two bugs: a mixed-dialect registry bound the default shard's converters to every shard
> (a PG-default + MySQL-shard setup would send `UUID` where `CHAR(36)` was required), and
> `count(shard)` / `findAll(shard)` / `findByActionId(shard)` silently queried the *default* shard
> whenever no transaction was open.
>
> Also removed real duplication — 39 static field constants became 21 (9 shared + 4x3), since only
> `id`, `action_id`, `action_params` and `payload` actually differ by dialect. AGENTS.md cites this
> class and the local-event-handler one as reference implementations of the same pattern; that is
> now true rather than half-true.
>
> A sweep for the same defect elsewhere found none. The four remaining default-shard references in
> main sources are `AbstractRepository`'s no-arg `db()` / `readonlyDb()` / `txDb()` overloads, where
> "no shard specified means default shard" is the documented contract, plus the
> `DatabaseRegistry.defaultTransactionManager()` accessor itself.
>
> Verified on all three dialects: `Pg` / `Mysql` / `Mariadb` `SingleTableJsonEventPersisterTest`
> (10 tests each), plus `SimpleWalletIntegrationTest` (4) and `ShardedWalletIntegrationTest` (12),
> and the full `ekbatan-core` unit + tracing suites.

**Where:** `ekbatan-core/.../single_table_json/EventEntityRepository.java`

The constructor resolves the dialect-specific field constants **and** a fallback
`DSLContext` once, from `databaseRegistry.defaultTransactionManager()`. `txDbElseDb(shard)`
then falls back to that default-shard context rather than the requested shard's.

Compare `ekbatan-events/local-event-handler/.../EventEntityRepository`, which resolves
`fieldsFor(shard)` per call and uses `db(shard)` / `readonlyDb(shard)`.

Impact today is nil in the normal path — outbox writes always run inside the per-shard
transaction, so `currentTransactionDbContext()` returns the right connection, and
single-dialect deployments are unaffected. But it contradicts the "mixed-dialect setups
are theoretically supported" claim in `AGENTS.md` / `docs/database/repositories.md`, and
its `count(shard)` / `findAll(shard)` / `findByActionId(shard)` would silently query the
**default** shard when no transaction is bound.

Status: **verified** by reading; not reproduced with a test.

### [x] 16. Dead code — unreachable private helpers in both local-event-handler repositories — **FIXED 2026-07-26**

`EventEntityRepository` and `EventNotificationRepository` (both in
`ekbatan-events/local-event-handler/.../repository/`) each carry a copy-pasted block of
private connection helpers, but only two are reachable in each:

- `EventEntityRepository` — only `readonlyDb(shard)` and `txDbElseDb(shard)` are called.
- `EventNotificationRepository` — only `db(shard)` and `txDbElseDb(shard)` are called.

Reachability analysis from the public entry points found **13** unreachable private methods, not
the ~12 first estimated — 6 in `EventEntityRepository`, 7 in `EventNotificationRepository`. They
looked live to a naive grep because they call *each other*: `txDbElseDb()` calls `txDb()` and
`db()`, and nothing calls `txDbElseDb()`.

Origin: both classes are standalone (they do not extend `AbstractRepository`), so each hand-copied
its full four-family helper surface, then used only the shard-qualified overloads — because a
standalone repository has no `ShardingStrategy` to derive a default shard from. `AGENTS.md` already
documents that: *"standalone repositories like `EventEntityRepository` use the
`(ShardIdentifier shard)` overloads explicitly."*

The sharpest reason to remove rather than keep: `EventNotificationRepository` carried a dead
`readonlyDb(shard)` directly beneath a class javadoc explaining that dispatch **must** read from
primary, or it risks re-invoking handlers for rows already marked complete. A replica accessor
sitting there was an invitation to reintroduce exactly that bug.

**Fixed:** all 13 deleted; the remaining shard-qualified accessors kept, with a comment in each
class recording why the no-arg family does not belong. A note in `EventNotificationRepository`
records why `readonlyDb` is deliberately absent. No visibility changed — every removed method was
`private`, so there is no API impact. Verified: `ekbatan-events-local-event-handler` compiles and
its unit tests pass; `PgLocalEventHandlerIntegrationTest` (9 tests, live Postgres) passes.

Status: **verified, fixed.**

### [x] 17. `UuidBinaryConverter` is unused — **RESOLVED 2026-07-26: keep**

> Decision: keep it as a supported opt-in. It is public API, has 8 passing unit tests, and
> `docs/database/jooq-codegen.md` already documents it as the `BINARY(16)` alternative for
> applications that want tighter index locality. "Unused" only ever meant "not wired into the
> framework's own migrations", which is by design — `CHAR(36)` is the default for grep-ability.
>
> Follow-through: `docs/database/multi-database.md` and its website copy described it as
> "dead code", which contradicted that status and discouraged a supported option. Both now
> describe it as a tested opt-in that simply isn't the default.

Nothing references it. `docs/database/multi-database.md` already acknowledges this
("exists in the codebase as dead code") and `docs/database/jooq-codegen.md` documents it
as an opt-in alternative — so this is a *decision to confirm* (keep as public API, or
drop), not a defect.

Status: **verified.**

---

## D. Notes, not defects

### [x] 18. Jackson 2 vs Jackson 3 split in the Debezium SMTs — **NOT A DEFECT**

`OutboxToAvroTransform` imports `com.fasterxml.jackson` (Jackson 2) while the whole
framework uses `tools.jackson` (Jackson 3). Correct — the SMTs run inside the Kafka
Connect runtime, which ships Jackson 2 — but it is an easy trap for anyone editing an SMT
and reaching for the framework's mapper. Possibly worth a one-line comment in the SMT.

### [x] 19. No connection-liveness checks in any `KeyedLockProvider` — **SUPERSEDED 2026-07-26**

> Group 2 established that liveness checks were the wrong fix: HikariCP already validates on borrow,
> and a pre-flight check cannot prevent a hang that happens *during* the wait. The real defects were
> non-interruptible blocking `acquire`, untyped failures, and cause-masking cleanup — all now fixed.
> The per-backend release policies catalogued below were preserved by that work.

None of the six providers calls `Connection.isClosed()` or `isValid(...)`. Context worth
carrying into task group 2, because the release/evict policies already differ per backend
and a uniform fix has to preserve each:

- **Postgres** — the only backend with a "dirty connection" concept (a failed
  `SET lock_timeout = 0` reset); evicts on dirty *or* on `pg_advisory_unlock` failure.
- **MySQL / MariaDB** — evict only when `RELEASE_LOCK` throws at the JDBC layer; a
  `NULL` or `0` return value is logged and the connection released normally.
- **Redis** — `backendRelease` **returns early on `ReleaseReason.WATCHDOG`**, deliberately
  leaving TTL expiry as the backend release path. Any liveness work must preserve this.
- **InProcess** — no connection involved at all.

---

## Coverage gap in this audit

The `website/` directory was only partially read. Covered: `learn/{index,getting-started,
your-first-action}`, `reference/{transaction-manager,action-executor,action,action-plan,
model,entity,model-event,repository}`, plus an API-signature sweep across every page.

**Not read:** `learn/complete-project-setup.mdx` (125 KB), `learn/{consuming-events,
sharding,sharding-strategies,sagas,native-image,models-and-entities,actions,outbox,
the-dual-write-trap}`, `reference/di/*`, `reference/events/*`, `reference/tables/*`,
`reference/runtime/*`, `index.astro`, and all `components/` + `layouts/` files —
roughly 60% of the site. Since the website duplicates `docs/` content (finding 1 appears
verbatim in both), the unread pages may carry further copies of the mismatches above.

Also unread and therefore unaudited: all 181 test sources, the 344 SQL migrations,
`ekbatan-native`, `ekbatan-flyway`, `ekbatan-annotation-processor`, `ekbatan-test-support`
(except `ActionSpec`), and `ekbatan-examples`.

---

### [x] 20. `AGENTS.md` describes an exception hierarchy that does not exist — **FIXED 2026-07-26**

> Replaced with the two classes that actually exist, plus pointers to `CrossShardException` and
> `LockAcquisitionException`.

`AGENTS.md:90` claimed:

> `PersistenceException` — Rich hierarchy with `ModelAware`, `ConstraintViolation` interfaces

None of it exists. `ekbatan-core` has exactly two exception classes in
`repository/exception/`: `StaleRecordException` and `EntityNotFoundException`.

**This one caused real damage.** The original `keyed-lock-liveness-detection` spec required
providers to "fail fast with a `PersistenceException` / `LockAcquisitionException`" — a requirement
written against a type that never existed, almost certainly because its author trusted this line.
A fictional documentation entry propagated directly into bad specification work.

Status: **verified, fixed.**
