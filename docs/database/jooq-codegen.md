# JOOQ codegen

What the framework actually does with your migrations at build time, what comes out the other side, and which converter to reach for when you write a forced-type entry. This page is the *modeling* reference — it's deliberately build-tool agnostic.

For the per-tool plugin syntax (the `jooq { withContainer { … } }` / `<plugin>` blocks themselves), see:

- **[JOOQ codegen on Gradle](../gradle/jooq-codegen.md)** — the `dev.monosoul.jooq-docker` plugin in detail, per-dialect `build.gradle.kts` blocks for PostgreSQL / MariaDB / MySQL.
- **[JOOQ codegen on Maven](../maven/jooq-codegen.md)** — the `fabric8 docker + flyway-maven + jooq-codegen-maven` chain, per-dialect `pom.xml` blocks for the same three dialects.

Both pages produce the same generated Java; only the build descriptor differs.

## What codegen produces

Three classes per table:

```
              ┌── your migration ──────────────────────────┐
              │  CREATE TABLE wallets (                    │
              │    id UUID PRIMARY KEY,                    │
              │    version BIGINT NOT NULL,                │
              │    balance DECIMAL(19, 2) NOT NULL,        │
              │    created_date TIMESTAMP NOT NULL,        │
              │    updated_date TIMESTAMP                  │
              │  );                                        │
              └────────────────────────────────────────────┘
                                  │
                                  ▼  jOOQ introspects the live schema
              ┌──────────────────────────────────────────────────────┐
              │  com.example.generated.jooq.tables.Wallets           │
              │    ↳ public static final Wallets WALLETS = …;        │
              │    ↳ public final TableField<…, UUID>       ID;      │
              │    ↳ public final TableField<…, Long>       VERSION; │
              │    ↳ public final TableField<…, BigDecimal> BALANCE; │
              │    ↳ public final TableField<…, Instant>    CREATED_DATE;│
              │    ↳ public final TableField<…, Instant>    UPDATED_DATE;│
              │                                                      │
              │  com.example.generated.jooq.tables.records.WalletsRecord│
              │    ↳ getId() / setId(UUID)                           │
              │    ↳ getBalance() / setBalance(BigDecimal)           │
              │    ↳ getCreatedDate() / setCreatedDate(Instant)      │
              │    ↳ …                                               │
              │                                                      │
              │  com.example.generated.jooq.indexes / keys / Tables  │
              └──────────────────────────────────────────────────────┘
```

Two things are happening:

1. **Table/field constants** (`WALLETS`, `WALLETS.ID`, `WALLETS.BALANCE`) — typed handles you use in `selectFrom(WALLETS).where(WALLETS.ID.eq(x))`. Compile-time-safe: rename a column, forget to migrate, and the rename won't compile.
2. **Record classes** (`WalletsRecord`) — one Java class per table, one field per column. The `Repository` base class converts between this and your domain model via `fromRecord` / `toRecord`.

The Java types in those `TableField<…, X>` declarations come from two sources: jOOQ's built-in mappings (`UUID → java.util.UUID`, `BIGINT → Long`, `DECIMAL → BigDecimal`), and your **forced-type** entries for the cases where the default mapping isn't what you want (`TIMESTAMP → Instant` not `LocalDateTime`; `JSONB → ObjectNode` not `JSONB`; `CHAR(36) → UUID` on MySQL only).

## What you do with it

Inside a repository:

```java
@EkbatanRepository
public class WalletRepository extends ModelRepository<Wallet, WalletsRecord, Wallets, UUID> {

    public WalletRepository(DatabaseRegistry databaseRegistry) {
        super(Wallet.class, WALLETS, WALLETS.ID, databaseRegistry);
    }

    // Custom query — typed field refs everywhere, no string SQL.
    public List<Wallet> findOpenByOwner(UUID ownerId) {
        return readonlyDb()
                .selectFrom(WALLETS)
                .where(WALLETS.OWNER_ID.eq(ownerId))
                .and(WALLETS.STATE.eq(WalletState.OPENED.name()))
                .fetch(this::fromRecord);
    }

    // Bridge: WalletsRecord → Wallet.
    @Override
    public Wallet fromRecord(WalletsRecord r) {
        return WalletBuilder.wallet()
                .id(Id.of(Wallet.class, r.getId()))
                .version(r.getVersion())
                .ownerId(r.getOwnerId())
                .balance(r.getBalance())
                .createdDate(r.getCreatedDate())          // Instant, courtesy of InstantConverter
                .updatedDate(r.getUpdatedDate())
                .build();
    }

    // Bridge: Wallet → WalletsRecord.
    @Override
    public WalletsRecord toRecord(Wallet w) {
        return new WalletsRecord(
                w.id.getValue(), w.version, w.ownerId,
                w.balance, w.createdDate, w.updatedDate);
    }
}
```

The framework's base classes — `ModelRepository`, `EntityRepository`, `AbstractRepository` — only know the generic types you parameterize them with (`<Wallet, WalletsRecord, Wallets, UUID>`). Codegen feeds those types; your `fromRecord` / `toRecord` do the lossy bridge.

## Where the generated classes land

Two conventions, one per database family:

### PostgreSQL — schema-aware packages

PG has real schemas (`public`, `eventlog`). The codegen maps each schema to a Java package:

```
com.example.generated.jooq
├── public_schema/
│   └── tables/
│       ├── Wallets.java
│       ├── Notifications.java
│       └── records/
│           ├── WalletsRecord.java
│           └── NotificationsRecord.java
└── eventlog_schema/
    └── tables/
        ├── Events.java
        └── records/EventsRecord.java
```

Two configuration choices flip this layout:

| Choice | Effect on Java | Effect on SQL |
|---|---|---|
| `schemaToPackageMapping.put("public", "public_schema")` | Java package is `public_schema/` (avoids the reserved keyword) | unchanged |
| `outputSchemaToDefault.add("public")` | unchanged | Generated SQL omits the `public.` qualifier — clean `SELECT * FROM wallets` |

So the canonical PG codegen does both: `public_schema` as the Java package name **and** the schema treated as default in SQL.

### MariaDB / MySQL — single-database, flat packages

These dialects conflate schema + database, so there's only ever one schema in scope at codegen time (the database you connected to). No `<dialect>_schema/` subpackage:

```
com.example.generated.jooq
└── tables/
    ├── Wallets.java
    ├── Notifications.java
    └── records/
        ├── WalletsRecord.java
        └── NotificationsRecord.java
```

The Java imports change:

```java
// PostgreSQL
import com.example.generated.jooq.public_schema.tables.Wallets;
import static com.example.generated.jooq.public_schema.tables.Wallets.WALLETS;

// MariaDB / MySQL
import com.example.generated.jooq.tables.Wallets;
import static com.example.generated.jooq.tables.Wallets.WALLETS;
```

Repositories targeting multiple dialects need parallel imports per dialect — see [Repository field-definition pattern](multi-database.md#repository-field-definition-pattern-cross-dialect-repos).

## What the framework's `eventlog` looks like in codegen

The eventlog tables behave differently depending on dialect:

- **PostgreSQL** — both `public` and `eventlog` are codegen'd into their respective subpackages. Application code rarely needs the eventlog classes directly (the framework writes them), but they're generated for completeness.
- **MariaDB / MySQL** — `eventlog` is a separate *database*, not a schema. Only the main database (`wallet`, `testdb`, etc.) is codegen'd; the framework's outbox writes use *manually-defined field constants* internally to avoid the need for a second codegen pass.

That's why the per-dialect Gradle / Maven blocks differ:

```kotlin
// PostgreSQL
schemas.set(listOf("public", "eventlog"))

// MariaDB / MySQL — only the main database
schemas.set(listOf("wallet"))
```

If you ever need to write a custom query against `eventlog` tables on MySQL/MariaDB, you'd add `eventlog` to `schemas` and reference the eventlog classes the same way as any other table. Almost no application does this; the framework's own field constants are sufficient.

## Framework-provided converters

Five converters in `io.ekbatan.core.persistence.jooq.converter` plus two MySQL-specific ones under the `mysql` subpackage. Pick by Java type and dialect.

| Converter | Java type | DB type (jOOQ) | Used on | Use case |
|---|---|---|---|---|
| `InstantConverter` | `Instant` | `LocalDateTime` (PG) / same (MariaDB/MySQL) | PG `TIMESTAMP`, MariaDB/MySQL `DATETIME(6)` | Every timestamp column in the framework's tables (`created_date`, `updated_date`, etc.). UTC enforced — see [Always UTC](multi-database.md#always-utc). |
| `JSONBObjectNodeConverter` | `ObjectNode` (Jackson 3) | `JSONB` | Postgres only | JSON *object*-shaped columns. The framework's `eventlog.events.payload` is this — every event payload is an object. |
| `JSONObjectNodeConverter` | `ObjectNode` (Jackson 3) | `JSON` | MariaDB / MySQL | Same role as JSONB on Postgres. Note the missing `B` in the class name. |
| `JSONBArrayNodeConverter` | `ArrayNode` (Jackson 3) | `JSONB` | Postgres only | JSON *array*-shaped columns. Use when your column stores a `[…]`, not a `{…}`. |
| `JSONArrayNodeConverter` | `ArrayNode` (Jackson 3) | `JSON` | MariaDB / MySQL | MariaDB/MySQL counterpart to `JSONBArrayNodeConverter`. |
| `mysql.UuidStringConverter` | `UUID` | `CHAR(36)` | MySQL only | MySQL has no native UUID type. Stores as the human-readable 8-4-4-4-12 hex form. **Default for MySQL.** |
| `mysql.UuidBinaryConverter` | `UUID` | `BINARY(16)` | MySQL only (and only if you opt in) | Alternative storage: half the bytes, tighter index locality. Round-trips UUIDv7 bit layouts losslessly — useful for sharded aggregates with embedded shard bits. Not used by default; available in the codebase if you need it. |

### Why two flavors per converter

`ObjectNode` and `ArrayNode` are both Jackson 3 `JsonNode` subtypes. The framework's event payloads are always objects (events have named fields), so `ObjectNode` is the workhorse — that's why every example uses it. Use `ArrayNode` only for columns whose data is always a JSON array (an audit-history rollup, a list of tags). If a column can be *either*, type it as the common ancestor `JsonNode` and write your own converter.

### Why `Instant` and not `LocalDateTime`

`Instant` is unambiguous — a UTC point in time, no zone needed. `LocalDateTime` is a clock reading without a zone, and round-tripping it through a `TIMESTAMP` column is fine *only* if the server clock is UTC. The framework picks `Instant` everywhere as a forcing function: the converter does the `ZoneOffset.UTC` math, and your domain code can't accidentally read a wall-clock value where it wanted a UTC instant.

### Why `JSONBObjectNodeConverter` ≠ `JSONObjectNodeConverter`

Same role, different JDBC binding:

- Postgres JDBC ships `org.jooq.JSONB`, which talks to the driver via `PGobject(type="jsonb", value=...)`. The converter wraps/unwraps `JSONB.valueOf(string)` ↔ `ObjectMapper.readValue(jsonb.data(), …)`.
- MariaDB / MySQL JDBC ship plain strings (MariaDB internally stores `JSON` as `LONGTEXT`-with-CHECK; MySQL has a native `JSON` storage type but the JDBC driver still exposes it via string). The converter wraps/unwraps `JSON.valueOf(string)` (note the type — `org.jooq.JSON`, no `B`) ↔ `ObjectMapper.readValue(json.data(), …)`.

Mixing them up — for example using `JSONBObjectNodeConverter` on MariaDB — fails at codegen time because the `forcedType` regex doesn't bind (the column reports as `JSON`, not `JSONB`).

## The forced-types pattern

The four framework converters (Instant + the two JSON shapes + MySQL UUID) are wired through the codegen plugin's *forced types* mechanism. Same idea on both Gradle and Maven; different syntax:

### Gradle

```kotlin
usingJavaConfig {
    database.withForcedTypes(
        ForcedType()
            .withUserType("java.time.Instant")
            .withConverter("io.ekbatan.core.persistence.jooq.converter.InstantConverter")
            .withIncludeTypes("(?i:DATETIME|TIMESTAMP)")
            .withIncludeExpression(".*"),
    )
}
```

### Maven

```xml
<forcedType>
    <userType>java.time.Instant</userType>
    <converter>io.ekbatan.core.persistence.jooq.converter.InstantConverter</converter>
    <includeTypes>(?i:DATETIME|TIMESTAMP)</includeTypes>
    <includeExpression>.*</includeExpression>
</forcedType>
```

Three knobs:

- **`userType`** — the Java type to use in the generated `TableField<…, X>`. Has to match the converter's `toType()`.
- **`converter`** — the fully-qualified converter class. Must have a no-arg constructor (jOOQ instantiates it reflectively).
- **`includeTypes`** — regex on the JDBC-reported column type. Case-insensitive variants (`(?i:DATETIME|TIMESTAMP)`) handle inconsistent casing across JDBC drivers.
- **`includeExpression`** — regex on the *column name* (qualified or unqualified). Use `.*` to match every column of the matching type; use `.*\.id|.*_id` to scope to UUID-shaped columns only (essential for the MySQL `CHAR(36) → UUID` converter — see [MySQL setup](mysql.md)).

### Per-dialect forced-type recipes

The exact set you need:

| Dialect | Forced types you need |
|---|---|
| PostgreSQL | `Instant` (for `TIMESTAMP`) + `ObjectNode` (for `JSONB`) — two entries |
| MariaDB | `Instant` (for `(?i:DATETIME|TIMESTAMP)`) + `ObjectNode` (for `(?i:JSON)`) — two entries |
| MySQL | `Instant` (for `(?i:DATETIME|TIMESTAMP)`) + `ObjectNode` (for `(?i:JSON)`) + `UUID` (for `CHAR(36)`, name `.*\.id|.*_id`) — **three entries** |

Skip an entry and you get the default jOOQ binding: `LocalDateTime` instead of `Instant`; `JSONB`/`JSON` (jOOQ's wrapper type) instead of `ObjectNode`; `String` instead of `UUID` on MySQL. The repository's `fromRecord` / `toRecord` then has to do the conversion in user code — usually a sign the codegen wasn't configured right.

## Why each dialect picks its types the way it does

The build-tool pages show *what* to put in each per-dialect block. Here's *why*:

### PostgreSQL — the smoothest fit

PG's type system maps closely to what the framework wants:

- **`UUID`** is native, jOOQ maps it to `java.util.UUID` directly. **No forced-type entry needed.**
- **`JSONB`** is a real binary-JSON storage type. Indexable, queryable (with `@>` containment), and the most efficient way to store JSON. `JSONBObjectNodeConverter` bridges to Jackson 3.
- **`TIMESTAMP WITHOUT TIME ZONE`** is what `LocalDateTime` maps to. `InstantConverter` reads it as UTC.
- **Schemas** are real: `eventlog` is a schema inside the application database. One JDBC connection, two namespaces, one codegen pass for both.

The PG block is the cleanest of the three. See [PostgreSQL setup](postgresql.md) for the full DDL and codegen example.

### MariaDB — close to PG, with two twists

- **`UUID`** has been native since MariaDB 10.7 — jOOQ maps it directly, **no forced-type entry needed.**
- **`JSON`** is stored internally as `LONGTEXT` with a CHECK constraint; the JDBC driver reports the type as `JSON`. The case-insensitive regex `(?i:JSON)` is required because driver-reported type names aren't consistent (`JSON` vs `json`).
- **`DATETIME(6)`** is the canonical six-digit-precision timestamp. The regex `(?i:DATETIME|TIMESTAMP)` covers both column types if you happen to use both.
- **`eventlog` is a separate database**, not a schema. Adds an init-script step (`mariadb_init.sql`) for the `GRANT`, and `V0000__create_eventlog_database.sql` to create the database before the eventlog tables.

See [MariaDB setup](mariadb.md) for the full DDL and init-script.

### MySQL — same as MariaDB, plus the UUID converter

- **No native `UUID` type.** Every UUID column is `CHAR(36) CHARACTER SET ascii`. `UuidStringConverter` does the bridge. **Third forced-type entry**, scoped to columns named `id` or ending in `_id` (without that scope, unrelated `CHAR(36)` columns get bound to `UUID` and break).
- Everything else matches MariaDB — `JSON`, `DATETIME(6)`, eventlog-as-separate-database.

See [MySQL setup](mysql.md) for the full DDL plus the `CHARACTER SET ascii` rationale.

## Modeling rules of thumb

- **One `Instant` forced-type for every dialect.** Never let `LocalDateTime` leak into your domain code.
- **One `ObjectNode` forced-type for every dialect.** `ObjectNode` for object-shaped JSON columns, `ArrayNode` only if you have an array-shaped column. Use the `B` variant on PG, the non-`B` on MariaDB/MySQL.
- **One UUID forced-type on MySQL only.** Scope it tightly (`.*\.id|.*_id`).
- **One codegen schema list per dialect.** PG: `listOf("public", "eventlog")`. MariaDB / MySQL: `listOf("<main-database>")`.
- **Match the package layout in your repository imports.** PG: `…public_schema.tables.Wallets`. MariaDB / MySQL: `…tables.Wallets`.

## Adding a new column

1. Author the migration in `src/main/resources/db/migration/V000N__*.sql`. Use the canonical type for the dialect (`TIMESTAMP` on PG, `DATETIME(6)` on MariaDB/MySQL; `JSONB` on PG, `JSON` on MariaDB/MySQL; `UUID` on PG/MariaDB, `CHAR(36) CHARACTER SET ascii` on MySQL — see the [Column-type cheatsheet](multi-database.md#column-type-cheatsheet)).
2. Re-run codegen (`./gradlew generateJooqClasses` or `./mvnw generate-sources`). The new column appears as a typed `TableField` on the existing `WALLETS` constant.
3. Update your `fromRecord` and `toRecord` in the repository.
4. Update your domain class (`Wallet`, `Notification`, …) to carry the new field. The `@AutoBuilder` processor picks up the new builder method automatically.

The build-tool pages (linked at the top) cover the codegen iteration story when you change a migration: how to force a clean re-codegen, what stale-container symptoms look like.

## See also

- [JOOQ codegen on Gradle](../gradle/jooq-codegen.md) — the `dev.monosoul.jooq-docker` plugin syntax, container config, troubleshooting
- [JOOQ codegen on Maven](../maven/jooq-codegen.md) — the `fabric8 docker + flyway-maven + jooq-codegen-maven` chain
- [Multi-database](multi-database.md) — UTC enforcement, the column-type cheatsheet, schema-vs-database, the dialect-switch repository pattern
- [Repositories on JOOQ](repositories.md) — how the generated table/record classes feed into `AbstractRepository`
- [PostgreSQL setup](postgresql.md) / [MariaDB setup](mariadb.md) / [MySQL setup](mysql.md) — per-dialect DDL, framework tables, gotchas
- [`eventlog.events`](tables/events.md) — the canonical event table shape these forced types map to

← Back to [Database](README.md) · [docs index](../README.md)
