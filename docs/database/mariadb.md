# MariaDB

MariaDB 10.7+ is a comfortable middle ground: native `UUID` (no converter needed for IDs) and modern `JSON`, but no partial indexes and no real schemas — what Postgres calls a *schema* becomes a separate *database*. This page is the one-stop setup reference. Cross-dialect background: [Multi-database](multi-database.md). Logical outbox shape: [Outbox schema](outbox-schema.md).

## Quick reference

| Java type | SQL column type | jOOQ converter (in `withForcedTypes`) |
|---|---|---|
| `java.util.UUID` | `UUID` (native, 10.7+) | none |
| `tools.jackson.databind.node.ObjectNode` | `JSON` | `JSONObjectNodeConverter` (no `B`) |
| `tools.jackson.databind.node.ArrayNode` | `JSON` | `JSONArrayNodeConverter` |
| `java.time.Instant` | `DATETIME(6)` | `InstantConverter` |
| `String` | `VARCHAR(N)` / `TEXT` | none |
| `Boolean` | `BOOLEAN` (alias for `TINYINT(1)`) | none |
| `Long` / `Integer` | `BIGINT` / `INT` | none |
| `BigDecimal` | `DECIMAL(p, s)` | none |

The `(6)` on `DATETIME` is the fractional-second precision — match Postgres's `TIMESTAMP` resolution. Never use `TIMESTAMP` for app-written columns; reserve it for db-scheduler's table.

## Migration order

MariaDB has no schemas, so `eventlog` is its own database — created by the first migration. The connecting user usually only has rights on the main database, so cross-database `GRANT`s must come from an init script (which runs as root before Flyway connects).

```
src/main/resources/
  mariadb_init.sql                                -- root-only GRANTs; mounted into the container at startup
  db/migration/
    V0000__create_eventlog_database.sql           -- CREATE DATABASE IF NOT EXISTS eventlog;
    V0001__eventlog.sql                           -- events + event_notifications + indexes (in eventlog.*)
    V0002__scheduled_tasks.sql                    -- only if you use ekbatan-distributed-jobs
    V0003__<domain>.sql                           -- your domain tables
```

### `mariadb_init.sql`

Grants the connecting user cross-database privileges. The container runs every `.sql` in `/docker-entrypoint-initdb.d/` as root before becoming ready — that's the only place this can live (Flyway connects as the named user, which can't grant itself anything).

```sql
GRANT ALL PRIVILEGES ON *.* TO 'wallet'@'%';
FLUSH PRIVILEGES;
```

Mount it via Testcontainers or docker-compose:

```java
.withCopyFileToContainer(
    MountableFile.forClasspathResource("mariadb_init.sql"),
    "/docker-entrypoint-initdb.d/mariadb_init.sql"
)
```

See [Schema vs database](multi-database.md#schema-vs-database) for the full idiom.

### `V0000__create_eventlog_database.sql`

```sql
CREATE DATABASE IF NOT EXISTS eventlog;
```

This runs as the named user. It only works because the init script above granted that user cross-database rights.

## Framework tables

### `eventlog.events` (always required)

```sql
CREATE TABLE eventlog.events (
    id              UUID         PRIMARY KEY,
    namespace       VARCHAR(255) NOT NULL,
    action_id       UUID         NOT NULL,
    action_name     VARCHAR(255) NOT NULL,
    action_params   JSON         NOT NULL,
    started_date    DATETIME(6)  NOT NULL,
    completion_date DATETIME(6)  NOT NULL,
    model_id        VARCHAR(255),
    model_type      VARCHAR(255),
    event_type      VARCHAR(255),
    payload         JSON,
    event_date      DATETIME(6)  NOT NULL,
    delivered       BOOLEAN      NOT NULL
);

CREATE INDEX idx_events_action_id ON eventlog.events(action_id);

-- No `WHERE delivered = FALSE` partial index — MariaDB doesn't support partial indexes.
-- The polling query filters on `delivered = FALSE` at the predicate level; the small
-- selectivity loss on the index is acceptable in practice.
CREATE INDEX events_pending_fanout ON eventlog.events (event_date);
```

### `eventlog.event_notifications` (only with `ekbatan-events-local-event-handler`)

```sql
CREATE TABLE eventlog.event_notifications (
    id              UUID         PRIMARY KEY,
    event_id        UUID         NOT NULL,
    handler_name    VARCHAR(255) NOT NULL,
    namespace       VARCHAR(255) NOT NULL,
    action_id       UUID         NOT NULL,
    action_name     VARCHAR(255) NOT NULL,
    action_params   JSON         NOT NULL,
    started_date    DATETIME(6)  NOT NULL,
    completion_date DATETIME(6)  NOT NULL,
    model_id        VARCHAR(255),
    model_type      VARCHAR(255),
    event_type      VARCHAR(255) NOT NULL,
    payload         JSON,
    event_date      DATETIME(6)  NOT NULL,
    state           VARCHAR(24)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_retry_at   DATETIME(6)  NOT NULL,
    created_date    DATETIME(6)  NOT NULL,
    updated_date    DATETIME(6)  NOT NULL,
    UNIQUE (event_id, handler_name)
);

CREATE INDEX event_notifications_due ON eventlog.event_notifications (next_retry_at);
```

### `scheduled_tasks` (only with `ekbatan-distributed-jobs`)

db-scheduler's required table — verbatim from [its repo](https://github.com/kagkarlsson/db-scheduler/blob/master/db-scheduler/src/main/resources/mysql_tables.sql). MySQL and MariaDB share the same DDL here.

```sql
create table scheduled_tasks (
    task_name VARCHAR(100) NOT NULL,
    task_instance VARCHAR(100) NOT NULL,
    task_data BLOB,
    execution_time TIMESTAMP(6) NOT NULL,
    picked BOOLEAN NOT NULL,
    picked_by VARCHAR(50),
    last_success TIMESTAMP(6) NULL,
    last_failure TIMESTAMP(6) NULL,
    consecutive_failures INT,
    last_heartbeat TIMESTAMP(6) NULL,
    version BIGINT NOT NULL,
    priority SMALLINT,
    PRIMARY KEY (task_name, task_instance),
    INDEX execution_time_idx (execution_time),
    INDEX last_heartbeat_idx (last_heartbeat),
    INDEX priority_execution_time_idx (priority, execution_time)
);
```

## Domain tables

`AbstractRepository` requires every domain table to carry five columns. Startup fails if any is missing. Reads automatically filter `state <> 'DELETED'`; updates carry `WHERE version = ?` and increment.

| Column | Type | Role |
|---|---|---|
| `id` | `UUID` (native) or your aggregate ID type | Primary key |
| `version` | `BIGINT NOT NULL` | Optimistic-locking counter — required, no default |
| `state` | `VARCHAR(N) NOT NULL` | Soft-delete discriminator. Set to `'DELETED'` for soft deletes (no separate `deleted_at` column) |
| `created_date` | `DATETIME(6) NOT NULL` | Set once on insert |
| `updated_date` | `DATETIME(6) NOT NULL` | Bumped on every update alongside `version` |

A minimal example (from `ekbatan-examples/quarkus-wallet-rest`):

```sql
CREATE TABLE wallets (
    id           UUID           PRIMARY KEY,
    version      BIGINT         NOT NULL,
    state        VARCHAR(24)    NOT NULL,
    owner_id     UUID           NOT NULL,
    currency     CHAR(3)        NOT NULL,
    balance      DECIMAL(19, 4) NOT NULL,
    created_date DATETIME(6)    NOT NULL,
    updated_date DATETIME(6)    NOT NULL
);

CREATE INDEX idx_wallets_owner_id ON wallets(owner_id);
```

## jOOQ codegen

Explicit `jooq { withContainer { … } }` is required — the plugin defaults to Postgres. Full reference: [JOOQ codegen → MariaDB](jooq-codegen.md#mariadb).

```kotlin
jooq {
    withContainer {
        image {
            name = "mariadb:11.8"
            envVars = mapOf(
                "MARIADB_ROOT_PASSWORD" to "root",
                "MARIADB_DATABASE" to "wallet",
            )
        }
        db {
            username = "root"; password = "root"; name = "wallet"; port = 3306
            jdbc { schema = "jdbc:mariadb"; driverClassName = "org.mariadb.jdbc.Driver" }
        }
    }
}

tasks {
    generateJooqClasses {
        // Only generate for the main 'wallet' database; the eventlog tables are accessed
        // through the framework's own field constants — no codegen for them needed.
        schemas.set(listOf("wallet"))
        basePackageName.set("io.example.<your_app>.generated.jooq")
        migrationLocations.setFromFilesystem("src/main/resources/db/migration")
        outputDirectory.set(project.layout.buildDirectory.dir("generated-jooq"))
        flywayProperties.put("flyway.placeholderReplacement", "false")
        includeFlywayTable.set(false)
        outputSchemaToDefault.add("wallet")          // generate at root; no `<schema>/` subpackage
        usingJavaConfig {
            database.withForcedTypes(
                ForcedType()
                    .withUserType("java.time.Instant")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.InstantConverter")
                    .withIncludeTypes("(?i:DATETIME|TIMESTAMP)")
                    .withIncludeExpression(".*"),
                ForcedType()
                    .withUserType("tools.jackson.databind.node.ObjectNode")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.JSONObjectNodeConverter")
                    .withIncludeTypes("(?i:JSON)")
                    .withIncludeExpression(".*"),
                // No UUID forced type needed — MariaDB has a native UUID type and jOOQ maps it
                // to java.util.UUID directly. Contrast with MySQL, which uses CHAR(36).
            )
        }
    }
}
```

Don't forget the build-time dependencies:

```kotlin
dependencies {
    implementation("org.mariadb.jdbc:mariadb-java-client:${project.property("mariadbJavaClientVersion")}")
    jooqCodegen("org.mariadb.jdbc:mariadb-java-client:${project.property("mariadbJavaClientVersion")}")
    implementation("org.flywaydb:flyway-mysql:${project.property("flywayVersion")}")
    jooqCodegen("org.flywaydb:flyway-mysql:${project.property("flywayVersion")}")
}
```

`flyway-mysql` (despite the name) is what Flyway uses to recognize MariaDB JDBC URLs.

## MariaDB-specific gotchas

- **Native UUID (10.7+) — no converter for IDs.** MariaDB has a real `UUID` type; jOOQ maps it directly to `java.util.UUID`. No `withForcedTypes` entry for UUID. On a 10.6-or-earlier server you'd need `CHAR(36)` + `UuidStringConverter` like MySQL.
- **`JSON`, not `JSONB`** — MariaDB has no JSONB. Use `JSONObjectNodeConverter` / `JSONArrayNodeConverter` (without the `B`). MariaDB stores `JSON` as `LONGTEXT` with a CHECK constraint internally; the JDBC driver reports it accordingly, which is why the forced-type regex `(?i:JSON)` works (and `(?i:JSON|LONGTEXT)` is necessary if you also have legitimate `LONGTEXT` columns). See [multi-database.md → Why MariaDB JSON columns still need a converter](multi-database.md#why-mariadb-json-columns-still-need-a-converter).
- **No partial indexes.** Drop the `WHERE delivered = FALSE` and `WHERE state IN (…)` clauses from the Postgres migrations when porting. The polling queries still filter at predicate time — the index just becomes slightly less selective.
- **`DATETIME(6)`, not `TIMESTAMP`.** App-written columns use `DATETIME(6)` (6-digit fractional seconds, no implicit `ON UPDATE` semantics). `TIMESTAMP` has a different value range and quirks around `NULL` / `DEFAULT CURRENT_TIMESTAMP` — reserve it for db-scheduler's table.
- **eventlog as a separate database.** Two consequences: a `V0000__create_eventlog_database.sql` migration, and an init script that grants the connecting user cross-database rights. Don't try to put `GRANT` statements in Flyway migrations — Flyway connects as the named user, which can't grant itself anything.
- **Set the container timezone to UTC.** `withEnv("TZ", "UTC")` on the container, or `serverTimezone=UTC` on the JDBC URL. Without this, `DATETIME` columns silently shift values between read and write.

## See also

- [Multi-database](multi-database.md) — cross-dialect background, column-type cheatsheet, init scripts, repository field-definition pattern
- [Outbox schema](outbox-schema.md) — the logical shape of `eventlog.events`
- [Repositories on JOOQ](repositories.md) — how `AbstractRepository` consumes the generated classes
- [JOOQ codegen](jooq-codegen.md) — the full `build.gradle.kts` reference for all dialects
- [Keyed locks](keyed-locks.md) — MariaDB `GET_LOCK()`-backed pessimistic locking
- Worked examples: [`ekbatan-examples/quarkus-wallet-rest`](../../ekbatan-examples/quarkus-wallet-rest), [`ekbatan-integration-tests/local-event-handler/mariadb`](../../ekbatan-integration-tests/local-event-handler/mariadb)

← Back to [Database](README.md) · [docs index](../README.md)
