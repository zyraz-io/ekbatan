# micronaut-wallet-rest-gradle-mariadb

A standalone Micronaut example using Ekbatan with **MariaDB**, built with **Gradle**. MariaDB dialect of the Micronaut Gradle triple — siblings are [`micronaut-wallet-rest-gradle-pg`](../micronaut-wallet-rest-gradle-pg) and [`micronaut-wallet-rest-gradle-mysql`](../micronaut-wallet-rest-gradle-mysql). For the Maven equivalent see [`micronaut-wallet-rest-maven-mariadb`](../micronaut-wallet-rest-maven-mariadb).

Native MariaDB `UUID` (no converter), `JSON`, `DATETIME(6)`; `eventlog` as a separate database created by V0000; `mariadb_init.sql` bind-mounted into `/docker-entrypoint-initdb.d/` for the cross-database GRANT.

## Run

```bash
docker compose up -d
./gradlew run
./gradlew test
```

## See also

- [`micronaut-wallet-rest-gradle-pg`](../micronaut-wallet-rest-gradle-pg) / [`micronaut-wallet-rest-gradle-mysql`](../micronaut-wallet-rest-gradle-mysql) — siblings
- [`micronaut-wallet-rest-maven-mariadb`](../micronaut-wallet-rest-maven-mariadb) — Maven counterpart
- [Ekbatan docs › Database › MariaDB](../../docs/database/mariadb.md)
