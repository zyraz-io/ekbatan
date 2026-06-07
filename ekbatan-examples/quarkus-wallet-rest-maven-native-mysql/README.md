# quarkus-wallet-rest-maven-native-mysql

A standalone Quarkus example that uses Ekbatan from Maven Central, backed by **MySQL**. The MySQL dialect of the Maven Quarkus triple — siblings are [`quarkus-wallet-rest-maven-pg`](../quarkus-wallet-rest-maven-pg) and [`quarkus-wallet-rest-maven-mariadb`](../quarkus-wallet-rest-maven-mariadb). Source code is byte-identical to the MariaDB sibling apart from the UUID column type and the extra `UuidStringConverter` `<forcedType>`.

## What it shows

| Surface | Class |
|---|---|
| `Model` (event-emitting) | `Wallet` |
| `Entity` (no events) | `Notification` |
| `Action` | `WalletCreateAction`, `WalletDepositMoneyAction`, `WalletCloseAction`, `CreateNotificationAction` |
| `EventHandler` | `WalletMoneyDepositedEventHandler` |
| `Repository` | `WalletRepository`, `NotificationRepository` |
| REST | `WalletResource` (JAX-RS `@Path`) |
| Flyway migration | `EkbatanShardFlywayMigrator` — observes `StartupEvent` with `@Priority(Interceptor.Priority.PLATFORM_BEFORE)` and calls `FlywayMigrator.migrate(shardingConfig)` |
| Integration test | `WalletResourceIntegrationTest` (`@QuarkusTest` + `@QuarkusTestResource(MySQLTestResource.class)` + RestAssured) |

## MySQL-specific bits

Concentrated in six places. The first five mirror the MariaDB sibling; the sixth is unique to MySQL.

| Concern | What changes vs. PG |
|---|---|
| Migration types | **`CHAR(36) CHARACTER SET ascii`** for UUID columns (no native UUID type); `JSON` for payloads; `DATETIME(6)` for timestamps; no partial indexes |
| `eventlog` | a separate **database**, not a schema; created by **`V0000__create_eventlog_database.sql`** before any other migration |
| Cross-database privilege | `src/main/resources/mysql_init.sql` grants the `wallet` user `ALL PRIVILEGES ON *.*`; mounted to `/docker-entrypoint-initdb.d/` and runs as root on container startup |
| `<forcedType>` entries in `pom.xml` | `InstantConverter` for `(?i:DATETIME|TIMESTAMP)`, `JSONObjectNodeConverter` for `(?i:JSON)`, **plus** `UuidStringConverter` for `CHAR(36)` columns matching `.*\.id|.*_id` |
| Generated jOOQ package | `io.example.wallet.generated.jooq.tables.*` (single-database codegen, no `_schema` subpackage) |
| **`UuidStringConverter`** (MySQL-only) | MySQL has no native `UUID` type. Every UUID column is `CHAR(36) CHARACTER SET ascii`, and `UuidStringConverter` bridges back to `java.util.UUID` at the jOOQ layer. The `includeExpression` scopes the converter to columns named `id` or ending in `_id` — without that, unrelated `CHAR(36)` columns would also bind to `UUID` and break at runtime. |

## Quarkus-on-Maven specifics

Identical to the PG sibling. No parent POM; `quarkus-bom` import in `<dependencyManagement>`. See [`quarkus-wallet-rest-maven-pg`](../quarkus-wallet-rest-maven-pg/README.md#quarkus-on-maven-specifics) for the full walkthrough.

## How the jOOQ codegen works on Maven

Three plugins composed in the lifecycle: fabric8 `docker-maven-plugin` (start `mysql:9.4.0`), `flyway-maven-plugin` (migrate, with `flyway-mysql` on the plugin classpath), `jooq-codegen-maven` (introspect with three `<forcedType>` entries — Instant, JSON, **CHAR(36)→UUID**).

The codegen container connects as **root** so the `V0000` migration's `CREATE DATABASE eventlog` succeeds without needing the `mysql_init.sql` GRANT script. The runtime / test containers use the `wallet` user *with* the init-script GRANT.

See [`docs/database/mysql.md`](../../docs/database/mysql.md) for the per-dialect cheatsheet (including why `CHAR(36)` over `BINARY(16)` for UUIDs) and [`docs/maven/jooq-codegen.md`](../../docs/maven/jooq-codegen.md) for the chain reference.

## Run locally

You need Docker installed.

```bash
docker compose up -d
./mvnw quarkus:dev
```

The API is at `http://localhost:8080/wallets`. Endpoints and curl examples are identical to the [PG sibling](../quarkus-wallet-rest-maven-pg/README.md#run-locally).

When you're done:

```bash
docker compose down
```

## Test

```bash
./mvnw verify
```

The integration test boots Quarkus with `@QuarkusTestResource(MySQLTestResource.class)`. The resource manager starts a Testcontainers `MySQLContainer` with `mysql_init.sql` bind-mounted into `/docker-entrypoint-initdb.d/`, runs Flyway migrations, and publishes the container's JDBC URL as runtime SmallRye Config properties — all *before* the Quarkus app context builds.

## See also

- [`quarkus-wallet-rest-maven-pg`](../quarkus-wallet-rest-maven-pg) — the PG sibling in the Maven Quarkus triple
- [`quarkus-wallet-rest-maven-mariadb`](../quarkus-wallet-rest-maven-mariadb) — the MariaDB sibling
- [`spring-boot-wallet-rest-maven-mysql`](../spring-boot-wallet-rest-maven-mysql) — the Spring Boot Maven + MySQL counterpart
- [`micronaut-wallet-rest-maven-mysql`](../micronaut-wallet-rest-maven-mysql) — the Micronaut Maven + MySQL counterpart
- [Ekbatan docs › Wiring with Quarkus](../../docs/wiring/quarkus.md)
- [Ekbatan docs › Database › MySQL](../../docs/database/mysql.md) — column types, the `CHAR(36)` UUID rationale, framework tables
- [Ekbatan docs › JOOQ codegen on Maven](../../docs/maven/jooq-codegen.md) — the codegen chain in detail
- [Ekbatan docs › Getting started with Maven](../../docs/maven/getting-started.md) — per-stack POM walkthroughs
