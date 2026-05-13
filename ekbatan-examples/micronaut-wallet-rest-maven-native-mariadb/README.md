# micronaut-wallet-rest-maven-native-mariadb

A GraalVM native-image variant of [`micronaut-wallet-rest-maven-mariadb`](../micronaut-wallet-rest-maven-mariadb). Same domain and same Micronaut wiring — the deltas are all about making the app boot inside the substrate VM. MariaDB dialect of the Maven Micronaut **native** triple — siblings are [`micronaut-wallet-rest-maven-native-pg`](../micronaut-wallet-rest-maven-native-pg) and [`micronaut-wallet-rest-maven-native-mysql`](../micronaut-wallet-rest-maven-native-mysql).

## What's different from the JVM sibling

Same native-machinery deltas as the PG sibling — see [`micronaut-wallet-rest-maven-native-pg/README.md`](../micronaut-wallet-rest-maven-native-pg/README.md#whats-different-from-the-jvm-sibling) for the full table and rationale. The two differences specific to this dialect:

| Concern | Native PG sibling | This module |
|---|---|---|
| `-H:IncludeResources` | `db/migration/.*\.sql` | adds `mariadb_init.sql` as a second pattern (kept in the image for completeness; runtime doesn't need it for the binary itself, only the Testcontainers test does — and that's classpath-loaded) |
| JDBC driver | `org.postgresql:postgresql` | `org.mariadb.jdbc:mariadb-java-client` — GraalVM reachability metadata is published for both; no extra hints needed |

## MariaDB-specific bits (recap from the JVM sibling)

| Concern | What's different vs. PG |
|---|---|
| Migration types | `UUID` (native, 10.7+) for IDs; `JSON` for payloads; `DATETIME(6)` for timestamps; no partial indexes |
| `eventlog` | a separate **database**, not a schema; created by `V0000__create_eventlog_database.sql` before any other migration |
| Cross-database privilege | `src/main/resources/mariadb_init.sql` grants the `wallet` user `ALL PRIVILEGES ON *.*`; mounted to `/docker-entrypoint-initdb.d/` and runs as root on container startup |
| `<forcedType>` entries in `pom.xml` | `InstantConverter` for `(?i:DATETIME|TIMESTAMP)`, `JSONObjectNodeConverter` for `(?i:JSON)`; **no UUID converter** |
| Generated jOOQ package | `io.example.wallet.generated.jooq.tables.*` (single-database codegen) |

## Build & run

GraalVM 25 must be available on `JAVA_HOME`.

### JVM mode

```bash
docker compose up -d
./mvnw mn:run
./mvnw verify   # JVM-mode integration test (MariaDB testcontainer + mariadb_init.sql bind-mount)
```

### Native binary

```bash
./mvnw package -Dpackaging=native-image
./target/micronaut-wallet-rest-maven-native-mariadb
```

## See also

- [`micronaut-wallet-rest-maven-mariadb`](../micronaut-wallet-rest-maven-mariadb) — the JVM sibling
- [`micronaut-wallet-rest-maven-native-pg`](../micronaut-wallet-rest-maven-native-pg) / [`micronaut-wallet-rest-maven-native-mysql`](../micronaut-wallet-rest-maven-native-mysql) — the PG / MySQL siblings in the native triple
- [`micronaut-wallet-rest-gradle-native-pg`](../micronaut-wallet-rest-gradle-native-pg) — the Gradle Micronaut native counterpart (PG)
- [Ekbatan docs › Runtime › GraalVM native-image](../../docs/runtime/native-image.md)
- [Ekbatan docs › Database › MariaDB](../../docs/database/mariadb.md)
- [Ekbatan docs › Getting started with Maven](../../docs/maven/getting-started.md)
