# quarkus-wallet-rest-gradle-native-pg

A standalone Quarkus example that uses Ekbatan with **PostgreSQL**, built with **Gradle**. PG dialect of the Quarkus Gradle triple — siblings are [`quarkus-wallet-rest-gradle-mariadb`](../quarkus-wallet-rest-gradle-mariadb) and [`quarkus-wallet-rest-gradle-mysql`](../quarkus-wallet-rest-gradle-mysql). For the Maven equivalent, see [`quarkus-wallet-rest-maven-pg`](../quarkus-wallet-rest-maven-pg).

Same domain as the other Quarkus examples — `Wallet` Model + `Notification` Entity + 4 Actions + `WalletMoneyDepositedEventHandler` + JAX-RS `WalletResource`. The PG dialect of this triple uses native `UUID`, `JSONB`, and `TIMESTAMP` columns with partial indexes; the `eventlog` namespace is a Postgres schema, created by `V0001`.

## Run locally

```bash
docker compose up -d
./gradlew quarkusDev
```

## Test

```bash
./gradlew test
```

The integration test uses `@QuarkusTest` + `@QuarkusTestResource(PostgresTestResource.class)` to spin up a Postgres testcontainer and run Flyway migrations before the Quarkus app context builds. RestAssured hits each endpoint and Awaitility polls until the listen-to-yourself handler creates the `Notification` row.

## See also

- [`quarkus-wallet-rest-gradle-mariadb`](../quarkus-wallet-rest-gradle-mariadb) / [`quarkus-wallet-rest-gradle-mysql`](../quarkus-wallet-rest-gradle-mysql) — MariaDB / MySQL siblings in this triple
- [`quarkus-wallet-rest-gradle-native-mariadb`](../quarkus-wallet-rest-gradle-native-mariadb) — Quarkus + GraalVM native variant
- [`quarkus-wallet-rest-maven-pg`](../quarkus-wallet-rest-maven-pg) — Maven counterpart
- [Ekbatan docs › Wiring with Quarkus](../../docs/wiring/quarkus.md)
- [Ekbatan docs › Database › PostgreSQL](../../docs/database/postgresql.md)
