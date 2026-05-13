# quarkus-wallet-rest

A standalone Quarkus 3.34 example that uses Ekbatan from Maven Central, backed by **MariaDB**.
Same domain shape as [`spring-boot-wallet-rest`](../spring-boot-wallet-rest) (Wallet model,
Notification entity, four actions, listen-to-yourself event handler, REST endpoint, integration
test) — adapted to Quarkus's CDI + JAX-RS surface and MariaDB's SQL.

> This is the JVM-only baseline. A `quarkus-wallet-rest-native` sibling for GraalVM native-image
> will follow.

## What it shows

| Surface | Class |
|---|---|
| `Model` (event-emitting) | `Wallet` |
| `Entity` (no events) | `Notification` |
| `Action` | `WalletCreateAction`, `WalletDepositMoneyAction`, `WalletCloseAction`, `CreateNotificationAction` |
| `EventHandler` | `WalletMoneyDepositedEventHandler` (listen-to-yourself; invokes `CreateNotificationAction`) |
| `Repository` | `WalletRepository`, `NotificationRepository` |
| REST (JAX-RS) | `WalletResource` |
| Flyway migration (CDI) | `FlywayConfiguration` — observes `StartupEvent` with `@Priority(LIBRARY_BEFORE)` so it fires before `JobRegistry` starts |
| Integration test | `WalletResourceIntegrationTest` (`@QuarkusTest` + `MariaDBTestResource`) |

## MariaDB specifics

This project is the example users should copy if they're targeting MariaDB or MySQL. The
framework supports the dialect natively; the example highlights the cross-dialect differences
in one place:

| Concern | This project |
|---|---|
| `JSONB` (Postgres) | `JSON` |
| `TIMESTAMP` (Postgres) | `DATETIME(6)` |
| Partial indexes (`WHERE delivered=FALSE`) | Full indexes (MariaDB doesn't support partial) |
| `CREATE SCHEMA eventlog` (Postgres) | `CREATE DATABASE eventlog` (`schema` ≡ `database` in MariaDB) |
| User privileges across databases | `mariadb_init.sql` runs `GRANT ALL PRIVILEGES ON *.* TO 'wallet'@'%'` during container init |
| JOOQ codegen container | `mariadb:11.8` (configured in `build.gradle.kts`'s `jooq { withContainer { ... } }` block) |
| JOOQ converters | `JSONObjectNodeConverter` for `JSON` (not `JSONB...`); no UUID converter needed (MariaDB has native `UUID`) |
| Flyway dialect | `org.flywaydb:flyway-mysql` (MariaDB uses the MySQL Flyway dialect) |
| JDBC driver | `org.mariadb.jdbc:mariadb-java-client` |

## Run locally

You only need Docker. `./gradlew quarkusDev` starts the MariaDB container declared in
`compose.yaml` and runs the app in dev mode (live reload):

```bash
./gradlew quarkusDev
```

The API is at `http://localhost:8080/wallets`.

```bash
# Create
curl -X POST http://localhost:8080/wallets \
    -H 'Content-Type: application/json' \
    -d '{"ownerId":"00000000-0000-0000-0000-000000000001","currency":"USD","initialBalance":"0.00"}'

# Deposit
curl -X POST http://localhost:8080/wallets/<id>/deposits \
    -H 'Content-Type: application/json' \
    -d '{"amount":"25.50","recipient":"alice@example.com"}'

# Read
curl http://localhost:8080/wallets/<id>

# Close
curl -X POST http://localhost:8080/wallets/<id>/close
```

## Test

```bash
./gradlew test
```

`MariaDBTestResource` boots a MariaDB testcontainer, runs Flyway migrations against it, then
publishes the JDBC URL/credentials as runtime properties. The Quarkus app starts on top and
hits the REST endpoints via REST-assured. The notification assertion uses Awaitility to wait
for the asynchronous listen-to-yourself dispatch.

## See also

- [`spring-boot-wallet-rest`](../spring-boot-wallet-rest) — the Spring Boot equivalent (Postgres).
- [Ekbatan docs › Multi-database](../../docs/database/multi-database.md) — the full dialect cheatsheet.
- [Ekbatan docs › Wiring with Quarkus](../../docs/wiring/quarkus.md) — the framework's Quarkus integration guide.
