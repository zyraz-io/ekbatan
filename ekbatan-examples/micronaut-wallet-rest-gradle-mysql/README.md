# micronaut-wallet-rest-gradle-mysql

A standalone Micronaut example using Ekbatan with **MySQL**, built with **Gradle**. MySQL dialect of the Micronaut Gradle triple — siblings are [`micronaut-wallet-rest-gradle-pg`](../micronaut-wallet-rest-gradle-pg) and [`micronaut-wallet-rest-gradle-mariadb`](../micronaut-wallet-rest-gradle-mariadb). For the Maven equivalent see [`micronaut-wallet-rest-maven-mysql`](../micronaut-wallet-rest-maven-mysql).

MySQL-specific bits: `CHAR(36) CHARACTER SET ascii` UUID columns (no native UUID type) bridged back to `java.util.UUID` via `UuidStringConverter`, `JSON`, `DATETIME(6)`; `eventlog` as a separate database created by V0000; `mysql_init.sql` bind-mounted into `/docker-entrypoint-initdb.d/` for the cross-database GRANT.

## Run

```bash
docker compose up -d
./gradlew run
./gradlew test
```

## See also

- [`micronaut-wallet-rest-gradle-pg`](../micronaut-wallet-rest-gradle-pg) / [`micronaut-wallet-rest-gradle-mariadb`](../micronaut-wallet-rest-gradle-mariadb) — siblings
- [`micronaut-wallet-rest-maven-mysql`](../micronaut-wallet-rest-maven-mysql) — Maven counterpart
- [Ekbatan docs › Database › MySQL](../../docs/database/mysql.md)
