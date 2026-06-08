# PostgreSQL

PostgreSQL is the most straightforward fit for Ekbatan: native `UUID` and `JSONB` types, real schemas, and partial indexes for the polling-query optimizations. This page is the one-stop reference for setting up a Postgres database that the framework will run against — migrations, codegen, gotchas. Cross-dialect background lives in [Multi-database](multi-database.md), and the canonical table reference lives in [Framework tables](tables.md).

## Quick reference

| Java type | SQL column type | jOOQ converter (in `withForcedTypes`) |
|---|---|---|
| `java.util.UUID` | `UUID` | none (jOOQ maps natively) |
| `tools.jackson.databind.node.ObjectNode` | `JSONB` | `JSONBObjectNodeConverter` |
| `tools.jackson.databind.node.ArrayNode` | `JSONB` | `JSONBArrayNodeConverter` |
| `java.time.Instant` | `TIMESTAMP` (without time zone) | `InstantConverter` |
| `String` | `VARCHAR(N)` / `TEXT` | none |
| `Boolean` | `BOOLEAN` | none |
| `Long` / `Integer` | `BIGINT` / `INT` | none |
| `BigDecimal` | `DECIMAL(p, s)` | none |

All converters live under `io.ekbatan.core.persistence.jooq.converter`. Never use `TIMESTAMPTZ` — see [Always UTC](multi-database.md#always-utc) for why.

## Migration order

Postgres has no separate "create the eventlog database" step — `eventlog` is just a schema in the connected database, created inline by the first migration. A typical project lays them out like this:

```
src/main/resources/db/migration/
  V0001__eventlog.sql        -- eventlog schema + events + event_notifications + indexes
  V0002__scheduled_tasks.sql -- only if you use ekbatan-distributed-jobs
  V0003__<domain>.sql        -- your domain tables (wallets, widgets, …)
```

## Framework tables

### `eventlog.events` (always required)

The event table — every action execution writes at least one row here. Sentinel rows have null `model_*` / `event_type` / `payload` (an action that emitted no events still gets recorded). The `delivered` flag is written `FALSE` on every insert and flipped `TRUE` by the in-process fan-out (no-op for Kafka-only deployments). Column details: [`eventlog.events`](tables/events.md).

```sql
CREATE SCHEMA IF NOT EXISTS eventlog;

CREATE TABLE eventlog.events (
    id              UUID         PRIMARY KEY,
    namespace       VARCHAR(255) NOT NULL,
    action_id       UUID         NOT NULL,
    action_name     VARCHAR(255) NOT NULL,
    action_params   JSONB        NOT NULL,
    started_date    TIMESTAMP    NOT NULL,
    completion_date TIMESTAMP    NOT NULL,
    model_id        VARCHAR(255),
    model_type      VARCHAR(255),
    event_type      VARCHAR(255),
    payload         JSONB,
    event_date      TIMESTAMP    NOT NULL,
    delivered       BOOLEAN      NOT NULL
);

CREATE INDEX idx_events_action_id ON eventlog.events(action_id);
```

### `eventlog.event_notifications` + partial index (only with `ekbatan-events-local-event-handler`)

The in-process fan-out reads `eventlog.events`, materializes one notification row per `(event × subscribed handler)`, and drives retries from there. The partial index keeps the polling scan cheap by only indexing the still-actionable rows.

```sql
CREATE INDEX events_undelivered
    ON eventlog.events (event_type, event_date)
    WHERE delivered = FALSE;

CREATE TABLE eventlog.event_notifications (
    id              UUID         PRIMARY KEY,
    event_id        UUID         NOT NULL,
    handler_name    VARCHAR(255) NOT NULL,
    namespace       VARCHAR(255) NOT NULL,
    action_id       UUID         NOT NULL,
    action_name     VARCHAR(255) NOT NULL,
    action_params   JSONB        NOT NULL,
    started_date    TIMESTAMP    NOT NULL,
    completion_date TIMESTAMP    NOT NULL,
    model_id        VARCHAR(255),
    model_type      VARCHAR(255),
    event_type      VARCHAR(255) NOT NULL,
    payload         JSONB,
    event_date      TIMESTAMP    NOT NULL,
    state           VARCHAR(24)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_retry_at   TIMESTAMP    NOT NULL,
    created_date    TIMESTAMP    NOT NULL,
    updated_date    TIMESTAMP    NOT NULL,
    UNIQUE (event_id, handler_name)
);

CREATE INDEX event_notifications_due
    ON eventlog.event_notifications (next_retry_at)
    WHERE state IN ('PENDING', 'FAILED');
```

### `scheduled_tasks` (only with `ekbatan-distributed-jobs`)

db-scheduler's required table — verbatim from [its repo](https://github.com/kagkarlsson/db-scheduler/blob/master/db-scheduler/src/main/resources/postgresql_tables.sql). This is the one place the framework steps off the always-`TIMESTAMP` rule — db-scheduler owns the table and uses `timestamp with time zone`.

```sql
create table scheduled_tasks (
    task_name text not null,
    task_instance text not null,
    task_data bytea,
    execution_time timestamp with time zone not null,
    picked BOOLEAN not null,
    picked_by text,
    last_success timestamp with time zone,
    last_failure timestamp with time zone,
    consecutive_failures INT,
    last_heartbeat timestamp with time zone,
    version BIGINT not null,
    priority SMALLINT,
    PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX execution_time_idx ON scheduled_tasks (execution_time);
CREATE INDEX last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
CREATE INDEX priority_execution_time_idx ON scheduled_tasks (priority desc, execution_time asc);
```

## Domain tables

`AbstractRepository` requires every domain table to carry five columns. Startup fails if any is missing. Reads automatically filter `state <> 'DELETED'`; updates carry `WHERE version = ?` and increment.

| Column | Type | Role |
|---|---|---|
| `id` | `UUID` (or your aggregate ID type) | Primary key |
| `version` | `BIGINT NOT NULL` | Optimistic-locking counter — required, no default |
| `state` | `VARCHAR(N) NOT NULL` | Soft-delete discriminator. Set to `'DELETED'` for soft deletes (no separate `deleted_at` column) |
| `created_date` | `TIMESTAMP NOT NULL` | Set once on insert |
| `updated_date` | `TIMESTAMP NOT NULL` | Bumped on every update alongside `version` |

A minimal example (from `ekbatan-examples/spring-boot-wallet-rest-gradle-pg`):

```sql
CREATE TABLE wallets (
    id           UUID           PRIMARY KEY,
    version      BIGINT         NOT NULL,
    state        VARCHAR(24)    NOT NULL,
    owner_id     UUID           NOT NULL,
    currency     CHAR(3)        NOT NULL,
    balance      DECIMAL(19, 4) NOT NULL,
    created_date TIMESTAMP      NOT NULL,
    updated_date TIMESTAMP      NOT NULL
);

CREATE INDEX idx_wallets_owner_id ON wallets(owner_id);
```

## jOOQ codegen

The Postgres codegen block — paste into your module's `build.gradle.kts`. The plugin's default container is Postgres, so no `jooq { withContainer { … } }` block is needed. Full reference: [JOOQ codegen on Gradle → PostgreSQL](../gradle/jooq-codegen.md#postgresql). **On Maven?** See [JOOQ codegen on Maven → PostgreSQL](../maven/jooq-codegen.md#postgresql) — the same output via the fabric8 + flyway-maven + jooq-codegen-maven chain.

```kotlin
tasks {
    generateJooqClasses {
        schemas.set(listOf("public", "eventlog"))
        basePackageName.set("io.example.<your_app>.generated.jooq")
        migrationLocations.setFromFilesystem("src/main/resources/db/migration")
        outputDirectory.set(project.layout.buildDirectory.dir("generated-jooq"))
        flywayProperties.put("flyway.placeholderReplacement", "false")
        includeFlywayTable.set(false)
        outputSchemaToDefault.add("public")
        schemaToPackageMapping.put("public", "public_schema")
        schemaToPackageMapping.put("eventlog", "eventlog_schema")
        usingJavaConfig {
            database.withForcedTypes(
                ForcedType()
                    .withUserType("java.time.Instant")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.InstantConverter")
                    .withIncludeTypes("TIMESTAMP")
                    .withIncludeExpression(".*"),
                ForcedType()
                    .withUserType("tools.jackson.databind.node.ObjectNode")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.JSONBObjectNodeConverter")
                    .withIncludeTypes("JSONB")
                    .withIncludeExpression(".*"),
            )
        }
    }
}
```

## Postgres-specific gotchas

- **Native `UUID`, no converter.** Postgres ships a real UUID type; jOOQ maps it directly to `java.util.UUID`. No `withForcedTypes` entry for UUID — unlike MySQL where it needs one.
- **`JSONB`, not `JSON`.** Use binary JSON everywhere. The converter is `JSONBObjectNodeConverter` (note the `B`); MySQL/MariaDB use `JSONObjectNodeConverter` (without the `B`).
- **Schema, not database.** `eventlog` is a *schema* inside the connected database, created via `CREATE SCHEMA IF NOT EXISTS eventlog;` in your first migration. No init script needed — the connecting user has create-schema rights by default.
- **Partial indexes are the whole point.** The framework's PG migrations use `WHERE delivered = FALSE` and `WHERE state IN ('PENDING', 'FAILED')` to keep polling scans cheap. Don't drop these when copying the migrations — they're the reason Postgres is the cleanest fit for the polling workload.
- **Timestamps use plain `TIMESTAMP`.** Never `TIMESTAMPTZ`. The DB container should run with `TZ=UTC` so `TIMESTAMP` columns round-trip with Java `Instant` losslessly. See [Always UTC](multi-database.md#always-utc).
- **Indexing JSONB.** If you'll query against JSONB fields, add a GIN index per the [Postgres JSONB indexing guide](https://www.postgresql.org/docs/current/datatype-json.html#JSON-INDEXING) — the framework doesn't ship one because most apps don't need it.

## See also

- [Multi-database](multi-database.md) — cross-dialect background, the column-type cheatsheet, init scripts, repository field-definition pattern
- [`eventlog.events`](tables/events.md) — the logical shape of the event table and what each column means
- [Repositories on JOOQ](repositories.md) — how `AbstractRepository` consumes the generated classes
- [JOOQ codegen](jooq-codegen.md) — what codegen generates, the converters, per-dialect modeling rationale
- [JOOQ codegen on Gradle](../gradle/jooq-codegen.md) — the full `build.gradle.kts` reference for all dialects
- [JOOQ codegen on Maven](../maven/jooq-codegen.md) — the equivalent `pom.xml` plugin chain
- [Keyed locks](keyed-locks.md) — Postgres `pg_advisory_lock`-backed pessimistic locking
- Worked example: [`ekbatan-examples/spring-boot-wallet-rest-gradle-pg`](../../ekbatan-examples/spring-boot-wallet-rest-gradle-pg)

← Back to [Database](README.md) · [docs index](../README.md)
