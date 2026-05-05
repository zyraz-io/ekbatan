# Database

The persistence layer — everything that touches the actual SQL database. Where domain code goes through `Repository` and `TransactionManager`; where shards live; where the outbox table sits on disk; where dialect quirks and JOOQ codegen are configured.

For the framework's *concept* of the outbox (the atomic state + events story), see [The outbox: atomic state + events](../concepts/outbox.md). This category covers everything else about the database.

- **[Repositories on JOOQ](repositories.md)** — `db()` / `readonlyDb()` / `txDb()` / `txDbElseDb()`, soft delete, custom queries
- **[TransactionManager](transaction-manager.md)** — direct transactional access outside the Action pipeline (admin scripts, batch jobs, custom multi-step reads)
- **[Outbox schema](outbox-schema.md)** — the SQL DDL of `eventlog.events`, the `delivered` flag, `event_notifications`, indexes
- **[Sharding](sharding.md)** — group + member, `ShardedUUID`, custom `ShardingStrategy`, cross-shard rules
- **[Pessimistic locking via `KeyedLockProvider`](keyed-locks.md)** — five backends (Postgres, MySQL, MariaDB, Redis, in-process), reentrancy contract, the `lockConfig` slot
- **[Multi-database (PostgreSQL / MySQL / MariaDB)](multi-database.md)** — dialect cheatsheet, init scripts, partial indexes, the `dialect.family()` switch pattern
- **[JOOQ codegen](jooq-codegen.md)** — per-dialect `build.gradle.kts` blocks for the `dev.monosoul.jooq-docker` plugin

← Back to [docs index](../README.md) · [Top README](../../README.md)
