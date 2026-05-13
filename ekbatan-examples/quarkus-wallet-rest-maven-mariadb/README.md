# quarkus-wallet-rest-maven-mariadb

A standalone Quarkus example that uses Ekbatan from Maven Central, backed by **MariaDB**. The MariaDB dialect of the Maven Quarkus triple — siblings are [`quarkus-wallet-rest-maven-pg`](../quarkus-wallet-rest-maven-pg) and [`quarkus-wallet-rest-maven-mysql`](../quarkus-wallet-rest-maven-mysql). Same MariaDB dialect as the Gradle counterpart [`quarkus-wallet-rest-gradle-mariadb`](../quarkus-wallet-rest-gradle-mariadb); only `pom.xml` vs. `build.gradle.kts` differs.

## What it shows

| Surface | Class |
|---|---|
| `Model` (event-emitting) | `Wallet` |
| `Entity` (no events) | `Notification` |
| `Action` | `WalletCreateAction`, `WalletDepositMoneyAction`, `WalletCloseAction`, `CreateNotificationAction` |
| `EventHandler` | `WalletMoneyDepositedEventHandler` |
| `Repository` | `WalletRepository`, `NotificationRepository` |
| REST | `WalletResource` (JAX-RS `@Path`) |
| Flyway migration | `FlywayConfiguration` — `@ApplicationScoped` + `@Observes StartupEvent` with `@Priority(LIBRARY_BEFORE)` |
| Integration test | `WalletResourceIntegrationTest` (`@QuarkusTest` + `@QuarkusTestResource(MariaDBTestResource.class)` + RestAssured) |

## MariaDB-specific bits

Concentrated in five places. Everything else is identical to the PG sibling.

| Concern | What changes vs. PG |
|---|---|
| Migration types | `UUID` (native, 10.7+) for IDs; `JSON` for payloads; `DATETIME(6)` for timestamps; no partial indexes |
| `eventlog` | a separate **database**, not a schema; created by **`V0000__create_eventlog_database.sql`** before any other migration |
| Cross-database privilege | `src/main/resources/mariadb_init.sql` grants the `wallet` user `ALL PRIVILEGES ON *.*`; mounted to `/docker-entrypoint-initdb.d/` and runs as root on container startup |
| `<forcedType>` entries in `pom.xml` | `InstantConverter` for `(?i:DATETIME|TIMESTAMP)`, `JSONObjectNodeConverter` for `(?i:JSON)`; **no UUID converter** — MariaDB has a native `UUID` type |
| Generated jOOQ package | `io.example.wallet.generated.jooq.tables.*` (single-database codegen, no `_schema` subpackage) |

## Quarkus-on-Maven specifics

Identical to the PG sibling. No parent POM; `quarkus-bom` import in `<dependencyManagement>`. Standard `<annotationProcessorPaths>` with only `ekbatan-annotation-processor` (no `combine.children="append"` gymnastics — Quarkus has no parent-POM AP entries to extend). See [`quarkus-wallet-rest-maven-pg`](../quarkus-wallet-rest-maven-pg/README.md#quarkus-on-maven-specifics) for the full walkthrough.

## How the jOOQ codegen works on Maven

Three plugins composed in the lifecycle: fabric8 `docker-maven-plugin` (start `mariadb:11.8`), `flyway-maven-plugin` (migrate, with `flyway-mysql` on the plugin classpath — handles MariaDB), `jooq-codegen-maven` (introspect with Instant + JSON forced types).

The codegen container connects as **root** so the `V0000` migration's `CREATE DATABASE eventlog` succeeds without needing the `mariadb_init.sql` GRANT script. The runtime / test containers use the `wallet` user *with* the init-script GRANT.

See [`docs/database/mariadb.md`](../../docs/database/mariadb.md) for the per-dialect cheatsheet and [`docs/maven/jooq-codegen.md`](../../docs/maven/jooq-codegen.md) for the chain reference.

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

The integration test boots Quarkus with `@QuarkusTestResource(MariaDBTestResource.class)`. The resource manager starts a Testcontainers `MariaDBContainer` with `mariadb_init.sql` bind-mounted into `/docker-entrypoint-initdb.d/`, runs Flyway migrations, and publishes the container's JDBC URL as runtime SmallRye Config properties — all *before* the Quarkus app context builds.

## See also

- [`quarkus-wallet-rest-maven-pg`](../quarkus-wallet-rest-maven-pg) — the PG sibling in the Maven Quarkus triple
- [`quarkus-wallet-rest-maven-mysql`](../quarkus-wallet-rest-maven-mysql) — the MySQL sibling
- [`quarkus-wallet-rest-gradle-mariadb`](../quarkus-wallet-rest-gradle-mariadb) — the Gradle Quarkus + MariaDB counterpart
- [`spring-boot-wallet-rest-maven-mariadb`](../spring-boot-wallet-rest-maven-mariadb) — the Spring Boot Maven + MariaDB counterpart
- [`micronaut-wallet-rest-maven-mariadb`](../micronaut-wallet-rest-maven-mariadb) — the Micronaut Maven + MariaDB counterpart
- [Ekbatan docs › Wiring with Quarkus](../../docs/wiring/quarkus.md)
- [Ekbatan docs › Database › MariaDB](../../docs/database/mariadb.md) — column types, converters, framework tables
- [Ekbatan docs › JOOQ codegen on Maven](../../docs/maven/jooq-codegen.md) — the codegen chain in detail
- [Ekbatan docs › Getting started with Maven](../../docs/maven/getting-started.md) — per-stack POM walkthroughs
