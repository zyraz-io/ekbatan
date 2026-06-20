---
name: ekbatan-maintainer
description: Maintain and evolve the Ekbatan Java persistence/action framework. Use for implementing or reviewing changes to Ekbatan core, repositories, actions, event outbox behavior, sharding, keyed locks, distributed jobs, DI integrations, database migrations, docs that describe framework semantics, or release-facing library behavior.
---

# Ekbatan Maintainer

Use this skill when modifying the framework itself, not just an application that depends on Ekbatan.

## First Reads

Read these before editing:

- `AGENTS.md` for architecture, conventions, and durable project rules.
- `README.md` for public positioning and current published artifacts.
- Relevant docs under `docs/` when changing behavior that is documented.
- Relevant tests under `ekbatan-integration-tests/` before changing cross-database behavior.

For a compact refresher, read `references/architecture-map.md`.

## Maintainer Rules

- Treat Ekbatan as a library, not an application.
- Keep domain objects immutable. Mutations return new instances.
- Keep `Action.perform()` as intent declaration. It stages changes on `ActionPlan`; the executor persists after `perform()` returns.
- Do not add nested/composable actions.
- Do not spawn concurrent work inside `Action.perform()`.
- Preserve optimistic locking with mandatory version checks.
- Use JOOQ explicitly; do not introduce ORM/session semantics.
- Keep PostgreSQL, MySQL, and MariaDB behavior aligned unless a dialect difference is intentional and documented.
- Use `TIMESTAMP`/`DATETIME(6)` conventions from existing migrations. Do not introduce `TIMESTAMPTZ`.
- For custom repository writes, prefer `txDbElseDb(...)`. For replica-tolerant reads, use `readonlyDb(...)`.
- Acquire `KeyedLockProvider` leases in callers that invoke actions when the lock is meant to serialize committed writes.

## Workflow

1. Map the change to the owning module before editing.
2. Read nearby code and tests. Prefer existing local patterns over new abstractions.
3. Keep changes narrow. Avoid unrelated refactors or metadata churn.
4. Update docs when public semantics change.
5. Add or adjust tests at the narrowest useful level first; broaden when behavior crosses modules, dialects, sharding, or DI integrations.
6. Run focused tests first, then the relevant broader suite.
7. If the change touches examples or verification behavior, use `ekbatan-examples` or `ekbatan-verification` too.

## Common Checks

- Core-only changes: `./gradlew :ekbatan-core:test`.
- DI integration changes: run affected `ekbatan-di-*` and `ekbatan-integration-tests-di-*` tests.
- Repository/dialect changes: run affected `ekbatan-integration-tests-core-repo-*` tests.
- Keyed lock changes: run affected `ekbatan-integration-tests-keyed-lock-provider-*` tests.
- Local event handler changes: run affected `ekbatan-integration-tests-local-event-handler-*` tests.
- Full JVM confidence pass: use `ekbatan-verification`.

Prefer `rg` for search and preserve unrelated user changes.
