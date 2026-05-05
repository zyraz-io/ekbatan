# Multi-database (PostgreSQL / MySQL / MariaDB)

Ekbatan supports PostgreSQL, MySQL, and MariaDB out of the box. Most of the framework is dialect-agnostic — the differences are concentrated in three places: column types in your migrations, JOOQ converters in your repository field constants, and the codegen container that introspects your schema at build time.

For the JOOQ codegen `build.gradle.kts` blocks (one per dialect), see [JOOQ codegen](jooq-codegen.md). This page covers everything else.

## Always UTC

Every timestamp Ekbatan writes is UTC. This is enforced project-wide and is **not** negotiable per-table.

- **SQL column type — use `TIMESTAMP`, never `TIMESTAMPTZ`.** PostgreSQL's `TIMESTAMP WITHOUT TIME ZONE`, MySQL/MariaDB's `DATETIME(6)`. The DB server is pinned to UTC, so plain `TIMESTAMP` round-trips correctly with Java `Instant`. Mixing `TIMESTAMP` and `TIMESTAMPTZ` within Ekbatan creates subtle JOOQ codegen and converter inconsistencies.
- **Database server timezone** — set the DB container or machine to `TZ=UTC`. Otherwise `TIMESTAMP` columns silently shift values between read and write.
- **TestContainers** — always configure `.withEnv("TZ", "UTC")`.
- **JDBC connections** — for MySQL/MariaDB, consider adding `serverTimezone=UTC` to the JDBC URL if you can't set it on the container.

## Column-type cheatsheet

The reference for what DDL type, what `SQLDataType`, and what JOOQ converter to use for each Java type, per dialect. **Always consult this table before writing migrations or repository field definitions.**

| Java type | PostgreSQL DDL | PG `SQLDataType` | MariaDB DDL | MariaDB `SQLDataType` | MySQL DDL | MySQL `SQLDataType` | Converter |
|---|---|---|---|---|---|---|---|
| `UUID` | `UUID` | `UUID.class` | `UUID` | `UUID.class` | `CHAR(36) CHARACTER SET ascii` | `SQLDataType.CHAR(36).asConvertedDataType(new UuidStringConverter())` | `UuidStringConverter` (MySQL only) |
| `ObjectNode` | `JSONB` | `SQLDataType.JSONB.asConvertedDataType(new JSONBObjectNodeConverter())` | `JSON` | `SQLDataType.JSON.asConvertedDataType(new JSONObjectNodeConverter())` | `JSON` | `SQLDataType.JSON.asConvertedDataType(new JSONObjectNodeConverter())` | `JSONBObjectNodeConverter` (PG) / `JSONObjectNodeConverter` (MariaDB+MySQL) |
| `Instant` | `TIMESTAMP` | `SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter())` | `DATETIME(6)` | same | `DATETIME(6)` | same | `InstantConverter` (all dialects) |
| `String` | `VARCHAR(N)` / `TEXT` | `String.class` | same | same | same | same | none |
| `Boolean` | `BOOLEAN` | `Boolean.class` | `BOOLEAN` (alias for `TINYINT(1)`) | `Boolean.class` | `BOOLEAN` (alias for `TINYINT(1)`) | `Boolean.class` | none |
| `Long` | `BIGINT` | `Long.class` | `BIGINT` | `Long.class` | `BIGINT` | `Long.class` | none |
| `Integer` | `INT` | `Integer.class` | `INT` | `Integer.class` | `INT` | `Integer.class` | none |
| `BigDecimal` | `DECIMAL(p, s)` | `BigDecimal.class` | `DECIMAL(p, s)` | `BigDecimal.class` | `DECIMAL(p, s)` | `BigDecimal.class` | none |

### Why MySQL needs `CHARACTER SET ascii` on UUID columns

UUID strings are pure 7-bit ASCII (8-4-4-4-12 hex with hyphens). Pinning the charset to ASCII keeps each char at one byte (vs. 3–4 under `utf8mb4`), tightens index locality, and avoids accidental collation rules being applied. PostgreSQL's native `UUID` and MariaDB's `UUID` (≥ 10.7) bypass charset entirely.

### Why MariaDB JSON columns still need a converter

MariaDB stores `JSON` as `LONGTEXT` with a CHECK constraint internally, and the JDBC driver reports the type accordingly. The forced-type entry in your `generateJooqClasses` block must use `(?i:JSON)` (or `(?i:JSON|LONGTEXT)` if you have legitimate `LONGTEXT` columns) and bind `JSONObjectNodeConverter`. See [JOOQ codegen](jooq-codegen.md) for the full block.

### Why MySQL UUID converter is `CHAR(36)`-shaped, not `BINARY(16)`

Ekbatan picks the human-readable form to keep query logs, raw JDBC dumps, and cross-dialect IDs grep-able. The `BINARY(16)` form would be more compact but isn't currently used anywhere in the project (a `UuidBinaryConverter` exists in the codebase as dead code).

## Schema vs database

In PostgreSQL, `eventlog` is a **schema** inside the connected database — created via `CREATE SCHEMA IF NOT EXISTS eventlog;` in a Flyway migration. No init script needed.

In MariaDB and MySQL, "schema" and "database" are synonyms; there is no second-level grouping. The `eventlog` namespace becomes a separate database. Two consequences:

1. The named test database (e.g. `testdb`) is created by `MARIADB_DATABASE` / `MYSQL_DATABASE`. The `eventlog` database must be created separately, via the very first Flyway migration:
   ```sql
   -- V0000__create_eventlog_schema.sql
   CREATE DATABASE IF NOT EXISTS eventlog;
   ```
2. The named test user (e.g. `test`) only has rights on the named database by default. Use a docker-entrypoint init script mounted at `/docker-entrypoint-initdb.d/`:
   ```sql
   -- mariadb_init.sql / mysql_init.sql
   GRANT ALL PRIVILEGES ON *.* TO 'test'@'%';
   FLUSH PRIVILEGES;
   ```
   Mount it via `withCopyFileToContainer(MountableFile.forClasspathResource("mariadb_init.sql"), "/docker-entrypoint-initdb.d/mariadb_init.sql")`. The script runs as root before the container becomes ready.

   Don't put privilege grants in Flyway migrations — they require root, and Flyway connects as the test user.

## Partial indexes (PostgreSQL only)

The framework's PG migrations use partial indexes to keep "due / pending" sweep queries cheap:

```sql
CREATE INDEX events_pending_fanout
    ON eventlog.events (event_date)
    WHERE delivered = FALSE;

CREATE INDEX event_notifications_due
    ON eventlog.event_notifications (next_retry_at)
    WHERE state IN ('PENDING', 'FAILED');
```

For the MariaDB/MySQL equivalents, **drop the `WHERE` clause** and accept a full index. The selectivity loss is small in practice (the polling query already filters on `next_retry_at <= now()` plus state, and the index covers the leading column).

## Repository field-definition pattern (cross-dialect repos)

When a repository targets multiple dialects, define field constants in three parallel sets — `PG_*`, `MARIADB_*`, `MYSQL_*` — but **only for fields whose `SQLDataType` actually differs** (UUID and JSON columns). Keep dialect-neutral fields (`String`, `Instant`, `Boolean`, `Integer`, `Long`) as a single shared constant.

In the constructor, switch on `dialect.family()`:

```java
if (defaultTm.dialect.family() == SQLDialect.MYSQL) {
    this.idField = MYSQL_ID;
    this.payloadField = MYSQL_PAYLOAD;
    // …
} else if (defaultTm.dialect.family() == SQLDialect.MARIADB) {
    this.idField = MARIADB_ID;
    this.payloadField = MARIADB_PAYLOAD;
    // …
} else {
    this.idField = PG_ID;
    this.payloadField = PG_PAYLOAD;
    // …
}
```

Reference implementations: `ekbatan-core/.../single_table_json/EventEntityRepository` and `ekbatan-events/local-event-handler/.../EventEntityRepository`.

## Adding a new database

1. Create a test subproject under `ekbatan-integration-tests/core-repo` (use an existing PG/MySQL/MariaDB module as a template).
2. Author dialect-specific Flyway migrations in `src/test/resources/db/migration`.
3. If the dialect needs new converters, add them under `ekbatan-core/.../persistence/jooq/converter/<dialect>/`.
4. Implement a `DummyRepository` for the new dialect with the right field-constant set.
5. Create a test runner extending `BaseRepositoryTest`.
6. If the dialect requires new SQL strategies (e.g. a different idiom for batch update), branch on `dialect.family()` in `AbstractRepository`.
7. Add the per-dialect [JOOQ codegen](jooq-codegen.md) `build.gradle.kts` block to the new test module.

## See also

- [JOOQ codegen](jooq-codegen.md) — the per-dialect `build.gradle.kts` blocks
- [Repositories on JOOQ](repositories.md) — how field constants and converters fit together
- [The outbox: atomic state + events](../concepts/outbox.md) — the framework's canonical schema this page enables you to author
- [Outbox schema](outbox-schema.md) — the on-disk shape of `eventlog.events` and friends
- [GraalVM native-image](../runtime/native-image.md) — Flyway and JDBC driver native-image considerations
