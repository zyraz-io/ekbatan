# Core concepts

The pieces every Ekbatan user touches. Read these first.

- **[The outbox: atomic state + events](outbox.md)** — the framework's atomic state-and-events guarantee
- **[Actions, ActionPlan, ActionExecutor](actions.md)** — the two-phase lifecycle, retries, no nesting, single-threaded perform
- **[Models and Entities](models-and-entities.md)** — when to use which, immutability, `@AutoBuilder`, optimistic locking
- **[Sagas: chaining committed actions](sagas.md)** — how to split multi-step workflows into durable actions and compensation steps
- **[Sharding strategies](sharding.md)** — routing, cross-shard rules, and why cross-shard actions are rejected by default

For the database-layer abstractions (`Repository`, `TransactionManager`, the outbox SQL schema, sharding, dialect support, JOOQ codegen), see **[Database](../database/README.md)**.

← Back to [docs index](../README.md) · [Top README](../../README.md)
