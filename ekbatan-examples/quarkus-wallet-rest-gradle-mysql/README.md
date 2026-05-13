# quarkus-wallet-rest-gradle-mysql

A standalone Quarkus example that uses Ekbatan with **MySQL**, built with **Gradle**. MySQL dialect of the Quarkus Gradle triple — siblings are [`quarkus-wallet-rest-gradle-pg`](../quarkus-wallet-rest-gradle-pg) and [`quarkus-wallet-rest-gradle-mariadb`](../quarkus-wallet-rest-gradle-mariadb). For the Maven equivalent, see [`quarkus-wallet-rest-maven-mysql`](../quarkus-wallet-rest-maven-mysql).

MySQL-specific bits: `CHAR(36) CHARACTER SET ascii` UUID columns (no native UUID type) bridged back to `java.util.UUID` via `UuidStringConverter`, `JSON` payloads, `DATETIME(6)` timestamps, `eventlog` as a separate database (created by V0000 with a `mysql_init.sql` GRANT mounted into `/docker-entrypoint-initdb.d/`).

## Run locally

```bash
docker compose up -d
./gradlew quarkusDev
```

## Test

```bash
./gradlew test
```

## See also

- [`quarkus-wallet-rest-gradle-pg`](../quarkus-wallet-rest-gradle-pg) / [`quarkus-wallet-rest-gradle-mariadb`](../quarkus-wallet-rest-gradle-mariadb) — siblings
- [`quarkus-wallet-rest-maven-mysql`](../quarkus-wallet-rest-maven-mysql) — Maven counterpart
- [Ekbatan docs › Wiring with Quarkus](../../docs/wiring/quarkus.md)
- [Ekbatan docs › Database › MySQL](../../docs/database/mysql.md)
