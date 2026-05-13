# micronaut-wallet-rest-maven-native-mysql

A GraalVM native-image variant of [`micronaut-wallet-rest-maven-mysql`](../micronaut-wallet-rest-maven-mysql). Same domain and same Micronaut wiring — the deltas are all about making the app boot inside the substrate VM. MySQL dialect of the Maven Micronaut **native** triple — siblings are [`micronaut-wallet-rest-maven-native-pg`](../micronaut-wallet-rest-maven-native-pg) and [`micronaut-wallet-rest-maven-native-mariadb`](../micronaut-wallet-rest-maven-native-mariadb).

## What's different from the JVM sibling

Same native-machinery deltas as the PG sibling — see [`micronaut-wallet-rest-maven-native-pg/README.md`](../micronaut-wallet-rest-maven-native-pg/README.md#whats-different-from-the-jvm-sibling) for the full table and rationale. Dialect-specific:

| Concern | Native PG sibling | This module |
|---|---|---|
| `-H:IncludeResources` | `db/migration/.*\.sql` | adds `mysql_init.sql` as a second pattern |
| JDBC driver | `org.postgresql:postgresql` | `com.mysql:mysql-connector-j` — GraalVM reachability metadata is published |
| Extra `<forcedType>` in codegen | (none) | `UuidStringConverter` for `CHAR(36)` → `UUID` (MySQL has no native UUID type) — same as the JVM MySQL sibling |

## MySQL-specific bits (recap from the JVM sibling)

| Concern | What's different vs. PG |
|---|---|
| Migration types | **`CHAR(36) CHARACTER SET ascii`** for UUID columns (no native UUID type); `JSON` for payloads; `DATETIME(6)` for timestamps; no partial indexes |
| `eventlog` | a separate **database**, not a schema; created by `V0000__create_eventlog_database.sql` |
| Cross-database privilege | `src/main/resources/mysql_init.sql` grants the `wallet` user `ALL PRIVILEGES ON *.*` |
| `<forcedType>` entries in `pom.xml` | `InstantConverter`, `JSONObjectNodeConverter`, **plus** `UuidStringConverter` for `CHAR(36)` matching `.*\.id|.*_id` |
| Generated jOOQ package | `io.example.wallet.generated.jooq.tables.*` (single-database codegen) |

## Build & run

GraalVM 25 must be available on `JAVA_HOME`.

### JVM mode

```bash
docker compose up -d
./mvnw mn:run
./mvnw verify   # JVM-mode integration test (MySQL testcontainer + mysql_init.sql bind-mount)
```

### Native binary

```bash
./mvnw package -Dpackaging=native-image
./target/micronaut-wallet-rest-maven-native-mysql
```

## See also

- [`micronaut-wallet-rest-maven-mysql`](../micronaut-wallet-rest-maven-mysql) — the JVM sibling
- [`micronaut-wallet-rest-maven-native-pg`](../micronaut-wallet-rest-maven-native-pg) / [`micronaut-wallet-rest-maven-native-mariadb`](../micronaut-wallet-rest-maven-native-mariadb) — the PG / MariaDB siblings in the native triple
- [`spring-boot-wallet-rest-gradle-mysql`](../spring-boot-wallet-rest-gradle-mysql) — the Gradle Spring Boot + MySQL counterpart (JVM-mode)
- [Ekbatan docs › Runtime › GraalVM native-image](../../docs/runtime/native-image.md)
- [Ekbatan docs › Database › MySQL](../../docs/database/mysql.md) — column types, the `CHAR(36)` UUID rationale
- [Ekbatan docs › Getting started with Maven](../../docs/maven/getting-started.md)
