# spring-boot-wallet-rest-gradle-native-mariadb

A standalone Spring Boot example that uses Ekbatan with **MariaDB** — same Java code as the Postgres-backed [`spring-boot-wallet-rest-gradle-pg`](../spring-boot-wallet-rest-gradle-pg), the differences are entirely in the build descriptor and the SQL migrations.

The MariaDB dialect of the Spring Boot Gradle triple — siblings are [`spring-boot-wallet-rest-gradle-pg`](../spring-boot-wallet-rest-gradle-pg) and [`spring-boot-wallet-rest-gradle-mysql`](../spring-boot-wallet-rest-gradle-mysql).

## What it shows

| Surface | Class |
|---|---|
| `Model` (event-emitting) | `Wallet` |
| `Entity` (no events) | `Notification` |
| `Action` | `WalletCreateAction`, `WalletDepositMoneyAction`, `WalletCloseAction`, `CreateNotificationAction` |
| `EventHandler` | `WalletMoneyDepositedEventHandler` |
| `Repository` | `WalletRepository`, `NotificationRepository` |
| REST | `WalletController` |
| Flyway migration | `EkbatanShardFlywayMigrator` — runs `FlywayMigrator.migrate(shardingConfig)` over every configured shard |
| Integration test | `WalletControllerIntegrationTest` (Testcontainers + Awaitility) |

The framework abstracts the dialect — every Java file is byte-identical to the Postgres sibling. Only the SQL and the build descriptor change.

## MariaDB-specific bits

1. **Two databases, not two schemas.** MariaDB has no separate concept of schema, so what Postgres calls `eventlog` (a schema in the main DB) becomes a separate database. The first Flyway migration (`V0000__create_eventlog_database.sql`) issues `CREATE DATABASE IF NOT EXISTS eventlog;`.
2. **Cross-database grants via init script.** The connecting user (`wallet`) only has rights on the `wallet` database by default — it cannot `CREATE DATABASE eventlog` without help. `mariadb_init.sql` (mounted into `/docker-entrypoint-initdb.d/`) grants the user `ALL PRIVILEGES ON *.*` *as root*, before the database becomes ready.
3. **`CHAR(36) CHARACTER SET ascii` for UUIDs.** MariaDB has no native UUID type. The `wallet`/`notification`/`event` tables use `CHAR(36)` with the ascii charset to keep each char at one byte. The jOOQ codegen block applies a forced type that maps `CHAR(36)` columns back to `java.util.UUID` via `UuidStringConverter`, so application code stays dialect-agnostic.
4. **`DATETIME(6)`, not `TIMESTAMP`.** Application columns use `DATETIME(6)` to match Postgres's `TIMESTAMP` precision. (db-scheduler's `scheduled_tasks` table is an exception — it ships with `TIMESTAMP(6)` and we keep it verbatim.)
5. **`JSON`, not `JSONB`.** MariaDB has no JSONB. The forced-type entry uses `JSONObjectNodeConverter` (no `B`).
6. **No partial indexes.** Postgres uses `WHERE delivered = FALSE` for the events index to keep polling cheap; MariaDB doesn't support partial indexes, so the full index includes the predicate columns: `(delivered, event_type, event_date)`.

For the per-dialect deep-dive, see [`docs/database/mariadb.md`](../../docs/database/mariadb.md).

## Run locally

You need Docker. `./gradlew bootRun` brings up the MariaDB container declared in `compose.yaml` via Spring Boot's docker-compose integration and tears it down on shutdown:

```bash
./gradlew bootRun
```

`mariadb_init.sql` is bind-mounted into the container so the V0000 migration succeeds. Flyway runs on startup; the API is at `http://localhost:8080/wallets`.

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

> **Re-running with a fresh DB**: `mariadb_init.sql` only runs on first container init (when the data volume is empty). If the schema gets into a bad state, `docker compose down -v` clears the volume so the init script runs again.

## Test

```bash
./gradlew test
```

The integration test boots a `MariaDBContainer` with the init script copied to `/docker-entrypoint-initdb.d/`, runs all four migrations, hits the REST endpoints, and asserts that the `Wallet` is updated and the `Notification` row eventually appears (the handler is async — Awaitility polls up to 15 seconds).

## See also

- [Ekbatan docs › Database › MariaDB](../../docs/database/mariadb.md) — column types, framework tables, native `UUID` support.
- [`spring-boot-wallet-rest-gradle-pg`](../spring-boot-wallet-rest-gradle-pg) — the Postgres sibling. Compare side-by-side to see exactly what changes for the dialect.
- [`quarkus-wallet-rest-gradle-mariadb`](../quarkus-wallet-rest-gradle-mariadb) — the MariaDB sibling. MariaDB has native UUID so its build skips the `UuidStringConverter` entry; otherwise the patterns are very close to this example.
- [Framework integration test](../../ekbatan-integration-tests/local-event-handler/mysql) — the canonical MariaDB wiring proof; this example mirrors its migrations and init-script pattern.
