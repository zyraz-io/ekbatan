# micronaut-wallet-rest-maven-mysql

A standalone Micronaut example that uses Ekbatan from Maven Central, backed by **MySQL**. The MySQL dialect of the Maven Micronaut triple — siblings are [`micronaut-wallet-rest-maven-pg`](../micronaut-wallet-rest-maven-pg) and [`micronaut-wallet-rest-maven-mariadb`](../micronaut-wallet-rest-maven-mariadb). Source code is byte-identical to the MariaDB sibling apart from the UUID column type and the extra `UuidStringConverter` `<forcedType>`; all dialect differences are concentrated in the build descriptor + migrations + init script.

## What it shows

| Surface | Class |
|---|---|
| `Model` (event-emitting) | `Wallet` |
| `Entity` (no events) | `Notification` |
| `Action` | `WalletCreateAction`, `WalletDepositMoneyAction`, `WalletCloseAction`, `CreateNotificationAction` |
| `EventHandler` | `WalletMoneyDepositedEventHandler` |
| `Repository` | `WalletRepository`, `NotificationRepository` |
| REST | `WalletController` (Micronaut `@Controller`) |
| Flyway migration | `FlywayConfiguration` — `@Context @Singleton @PostConstruct`, eager-initialized at context build time |
| Integration test | `WalletControllerIntegrationTest` (`@MicronautTest` + `TestPropertyProvider` + `MySQLContainer` + `withCopyFileToContainer`) |

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

## Micronaut-on-Maven specifics

Identical to the PG sibling. The critical bit is the `combine.children="append"` on `<annotationProcessorPaths>` (so the parent POM's `micronaut-inject-java` AP entry is preserved), and `ekbatan-micronaut` on the AP path (so the `EkbatanStereotypeVisitor` lifts `@EkbatanAction` etc. to `@Singleton`). See [`micronaut-wallet-rest-maven-pg`](../micronaut-wallet-rest-maven-pg/README.md#micronaut-on-maven-specifics) for the full walkthrough.

## How the jOOQ codegen works on Maven

Three plugins composed in the lifecycle: fabric8 `docker-maven-plugin` (start `mysql:9.4.0`), `flyway-maven-plugin` (migrate, with `flyway-mysql` on the plugin classpath), `jooq-codegen-maven` (introspect with three `<forcedType>` entries — Instant, JSON, **CHAR(36)→UUID**).

The codegen container connects as **root** so the `V0000` migration's `CREATE DATABASE eventlog` succeeds without needing the `mysql_init.sql` GRANT script. The runtime / test containers use the `wallet` user *with* the init-script GRANT.

See [`docs/database/mysql.md`](../../docs/database/mysql.md) for the per-dialect cheatsheet (including why `CHAR(36)` over `BINARY(16)`) and [`docs/maven/jooq-codegen.md`](../../docs/maven/jooq-codegen.md) for the chain reference.

## Run locally

You need Docker installed.

```bash
docker compose up -d
./mvnw mn:run
```

The API is at `http://localhost:8080/wallets`. Endpoints and curl examples are identical to the [PG sibling](../micronaut-wallet-rest-maven-pg/README.md#run-locally).

When you're done:

```bash
docker compose down
```

## Test

```bash
./mvnw verify
```

The integration test boots Micronaut with a Testcontainers `MySQLContainer`, mounts `mysql_init.sql` into `/docker-entrypoint-initdb.d/`, runs migrations through the `@Context`-eager `FlywayConfiguration`, hits each endpoint, and asserts that the `Wallet` is updated and the `Notification` row eventually appears.

## See also

- [`micronaut-wallet-rest-maven-pg`](../micronaut-wallet-rest-maven-pg) — the PG sibling in the Maven Micronaut triple
- [`micronaut-wallet-rest-maven-mariadb`](../micronaut-wallet-rest-maven-mariadb) — the MariaDB sibling
- [`spring-boot-wallet-rest-maven-mysql`](../spring-boot-wallet-rest-maven-mysql) — the Spring Boot Maven + MySQL counterpart
- [`spring-boot-wallet-rest-gradle-mysql`](../spring-boot-wallet-rest-gradle-mysql) — the Gradle Spring Boot + MySQL counterpart
- [Ekbatan docs › Wiring with Micronaut](../../docs/wiring/micronaut.md)
- [Ekbatan docs › Database › MySQL](../../docs/database/mysql.md) — column types, the `CHAR(36)` UUID rationale, framework tables
- [Ekbatan docs › JOOQ codegen on Maven](../../docs/maven/jooq-codegen.md) — the codegen chain in detail
- [Ekbatan docs › Getting started with Maven](../../docs/maven/getting-started.md) — per-stack POM walkthroughs
