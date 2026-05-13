# micronaut-wallet-rest-maven-mariadb

A standalone Micronaut example that uses Ekbatan from Maven Central, backed by **MariaDB**. The MariaDB dialect of the Maven Micronaut triple — siblings are [`micronaut-wallet-rest-maven-pg`](../micronaut-wallet-rest-maven-pg) and [`micronaut-wallet-rest-maven-mysql`](../micronaut-wallet-rest-maven-mysql). Source code is byte-identical to the PG sibling apart from one detail (the generated jOOQ package layout); all dialect differences are concentrated in the build descriptor + migrations + init script.

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
| Integration test | `WalletControllerIntegrationTest` (`@MicronautTest` + `TestPropertyProvider` + `MariaDBContainer` + `withCopyFileToContainer`) |

## MariaDB-specific bits

Concentrated in five places. Everything else is identical to the PG sibling.

| Concern | What changes vs. PG |
|---|---|
| Migration types | `UUID` (native, 10.7+) for IDs; `JSON` for payloads; `DATETIME(6)` for timestamps; no partial indexes |
| `eventlog` | a separate **database**, not a schema; created by **`V0000__create_eventlog_database.sql`** before any other migration |
| Cross-database privilege | `src/main/resources/mariadb_init.sql` grants the `wallet` user `ALL PRIVILEGES ON *.*`; mounted to `/docker-entrypoint-initdb.d/` and runs as root on container startup |
| `<forcedType>` entries in `pom.xml` | `InstantConverter` for `(?i:DATETIME|TIMESTAMP)`, `JSONObjectNodeConverter` for `(?i:JSON)`; **no UUID converter** — MariaDB has a native `UUID` type |
| Generated jOOQ package | `io.example.wallet.generated.jooq.tables.*` (single-database codegen, no `_schema` subpackage) |

## Micronaut-on-Maven specifics

Identical to the PG sibling. The critical bit is the `combine.children="append"` on `<annotationProcessorPaths>` (so the parent POM's `micronaut-inject-java` AP entry is preserved), and `ekbatan-micronaut` on the AP path (so the `EkbatanStereotypeVisitor` lifts `@EkbatanAction` etc. to `@Singleton`). See [`micronaut-wallet-rest-maven-pg`](../micronaut-wallet-rest-maven-pg/README.md#micronaut-on-maven-specifics) for the full walkthrough.

## How the jOOQ codegen works on Maven

The Gradle equivalents use [`dev.monosoul.jooq-docker`](https://github.com/monosoul/jooq-gradle-plugin) which bundles container + Flyway + codegen into a single plugin. There's no Maven equivalent, so this project composes three independent plugins instead — all running before `compile`:

| Phase | Plugin | What it does |
|---|---|---|
| `initialize` | `io.fabric8:docker-maven-plugin` | Starts an ephemeral `mariadb:11.8` container on port 13306. |
| `initialize` | `org.flywaydb:flyway-maven-plugin` | Runs `src/main/resources/db/migration/*.sql` against that container. Includes `flyway-mysql` (handles MariaDB too). |
| `generate-sources` | `org.jooq:jooq-codegen-maven` | Introspects the migrated schema and writes generated Java classes to `target/generated-sources/jooq`. |
| `prepare-package` | `io.fabric8:docker-maven-plugin` | Stops + removes the container. |

The codegen container connects as **root** so the `V0000` migration's `CREATE DATABASE eventlog` succeeds without needing the `mariadb_init.sql` GRANT script. The runtime / test containers use the `wallet` user *with* the init-script GRANT.

See [`docs/database/mariadb.md`](../../docs/database/mariadb.md) for the per-dialect cheatsheet and [`docs/maven/jooq-codegen.md`](../../docs/maven/jooq-codegen.md) for the chain reference.

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

The integration test boots Micronaut with a Testcontainers `MariaDBContainer`, mounts `mariadb_init.sql` into `/docker-entrypoint-initdb.d/`, runs migrations through the `@Context`-eager `FlywayConfiguration`, hits each endpoint, and asserts that the `Wallet` is updated and the `Notification` row eventually appears.

## See also

- [`micronaut-wallet-rest-maven-pg`](../micronaut-wallet-rest-maven-pg) — the PG sibling in the Maven Micronaut triple
- [`micronaut-wallet-rest-maven-mysql`](../micronaut-wallet-rest-maven-mysql) — the MySQL sibling
- [`spring-boot-wallet-rest-maven-mariadb`](../spring-boot-wallet-rest-maven-mariadb) — the Spring Boot Maven + MariaDB counterpart
- [Ekbatan docs › Wiring with Micronaut](../../docs/wiring/micronaut.md)
- [Ekbatan docs › Database › MariaDB](../../docs/database/mariadb.md) — column types, converters, framework tables
- [Ekbatan docs › JOOQ codegen on Maven](../../docs/maven/jooq-codegen.md) — the codegen chain in detail
- [Ekbatan docs › Getting started with Maven](../../docs/maven/getting-started.md) — per-stack POM walkthroughs
