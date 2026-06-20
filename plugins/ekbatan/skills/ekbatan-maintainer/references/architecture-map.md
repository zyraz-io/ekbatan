# Ekbatan Architecture Map

Use this as a compact orientation before working in the codebase. `AGENTS.md` remains the source of truth.

## Core Idea

Ekbatan is a Java persistence and action framework. It stores state changes and domain events atomically so the database can act as a reliable outbox.

An `Action` declares business intent. During `perform()`, it reads repositories and stages new immutable object snapshots on an `ActionPlan`. After `perform()` returns, `ActionExecutor` opens the transaction and persists domain rows plus event rows together.

## Main Modules

- `ekbatan-core`: domain abstractions, repositories, action execution, transaction management, sharding, keyed locks.
- `ekbatan-di`: Spring, Quarkus, and Micronaut integration annotations and bootstrapping.
- `ekbatan-events`: local event handler and streaming event payload modules.
- `ekbatan-distributed-jobs`: db-scheduler based clustered jobs.
- `ekbatan-keyed-lock-redis`: Redisson-backed `KeyedLockProvider`.
- `ekbatan-test-support`: public test helpers.
- `ekbatan-integration-tests`: Testcontainers-backed matrix for databases, DI, event pipeline, locks, jobs.
- `ekbatan-examples`: standalone applications across stack, build tool, dialect, native, sharding, saga, and job-worker variants.

## Behavioral Invariants

- `Model` changes produce `ModelEvent` records; `Entity` changes do not.
- Persistable objects are immutable and versioned.
- Updates use optimistic locking with `WHERE version = ?`.
- Soft deletion uses `state = DELETED`; normal reads filter deleted rows.
- Event rows are written to `eventlog.events`.
- No nested actions. Compose business work inside one action or orchestrate separate actions above the framework.
- No threads inside `Action.perform()`.
- Caller-side locks wrap `executor.execute(...)` when serializing committed writes.

## Database Conventions

- Support PostgreSQL, MySQL, and MariaDB.
- Use JOOQ and explicit `fromRecord` / `toRecord` mapping.
- Store timestamps as UTC instants using `TIMESTAMP` on PostgreSQL and `DATETIME(6)` on MySQL/MariaDB.
- Copy DDL conventions from existing migrations before adding new migrations.
- Avoid SQL defaults unless they clearly improve meaning and cross-dialect parity.

## Repository Helper Choice

- `db(...)`: primary connection, no transaction requirement.
- `readonlyDb(...)`: replica/secondary reads where lag is acceptable.
- `txDb()`: optional active transaction context.
- `txDbElseDb(...)`: active transaction if present, otherwise primary; use for custom writes.

## Sharding

Sharded IDs route writes. Scatter-gather condition reads use `dbs()` or `readonlyDbs()`. Cross-shard action writes are rejected unless `allowCrossShard=true`.
