# Maven

Ekbatan publishes 16 JARs to Maven Central and has no Gradle-only runtime requirement — every framework artifact works the same way in a Maven project. This category covers the build-tool surface that a Maven consumer actually has to write.

For the Gradle equivalent, see [Gradle](../gradle/README.md). The framework itself is build-tool-agnostic — only the codegen tooling differs: Ekbatan-on-Gradle uses [`dev.monosoul.jooq-docker`](https://github.com/monosoul/jooq-gradle-plugin), which bundles container + Flyway + jOOQ codegen into one plugin; Ekbatan-on-Maven composes three independent plugins (`fabric8 docker` + `flyway-maven` + `jooq-codegen-maven`). Both produce the same generated Java; only the build descriptor differs.

- **[Getting started with Maven](getting-started.md)** — per-stack `pom.xml` blocks for Spring Boot / Quarkus / Micronaut / plain Java, annotation processor wiring (including the Micronaut `combine.children="append"` gotcha), the `-parameters` compiler flag, Spring Boot BOM override for jOOQ, optional add-ons, Maven-property namespace pitfalls.
- **[jOOQ codegen on Maven](jooq-codegen.md)** — the three-plugin chain in detail, per-dialect `pom.xml` blocks for PostgreSQL / MariaDB / MySQL, ForcedType reference, container init scripts, the schema-to-package mapping caveat, container lifecycle, and a troubleshooting section.

For the framework-level concepts these pages assume you already know, see [docs/wiring/](../wiring/) (DI + auto-config) and [docs/database/](../database/README.md) (the canonical schema and dialect notes — build-tool agnostic).

Eighteen Maven wallet examples live under [`ekbatan-examples/`](../../ekbatan-examples/) — Spring Boot, Quarkus, and Micronaut across PostgreSQL/MariaDB/MySQL, with JVM and GraalVM native-image variants for each:

**Spring Boot** (`spring-boot-starter-parent` + `spring-boot-maven-plugin`):

- [`spring-boot-wallet-rest-maven-pg/`](../../ekbatan-examples/spring-boot-wallet-rest-maven-pg) — PostgreSQL (the canonical reference; every PG snippet on these pages traces back to it)
- [`spring-boot-wallet-rest-maven-mariadb/`](../../ekbatan-examples/spring-boot-wallet-rest-maven-mariadb) — MariaDB (native `UUID`, `JSON`, `DATETIME(6)`, eventlog as a separate database)
- [`spring-boot-wallet-rest-maven-mysql/`](../../ekbatan-examples/spring-boot-wallet-rest-maven-mysql) — MySQL (`CHAR(36)` UUID via `UuidStringConverter`, otherwise same shape as MariaDB)
- [`spring-boot-wallet-rest-maven-native-pg/`](../../ekbatan-examples/spring-boot-wallet-rest-maven-native-pg) / [`-mariadb`](../../ekbatan-examples/spring-boot-wallet-rest-maven-native-mariadb) / [`-mysql`](../../ekbatan-examples/spring-boot-wallet-rest-maven-native-mysql) — GraalVM native-image variants

**Quarkus** (`quarkus-bom` import, no parent POM; `quarkus-maven-plugin` with `<extensions>true</extensions>`):

- [`quarkus-wallet-rest-maven-pg/`](../../ekbatan-examples/quarkus-wallet-rest-maven-pg) — PostgreSQL (JAX-RS `@Path` resources; `@QuarkusTest` + `@QuarkusTestResource(PostgresTestResource.class)`)
- [`quarkus-wallet-rest-maven-mariadb/`](../../ekbatan-examples/quarkus-wallet-rest-maven-mariadb) — MariaDB
- [`quarkus-wallet-rest-maven-mysql/`](../../ekbatan-examples/quarkus-wallet-rest-maven-mysql) — MySQL
- [`quarkus-wallet-rest-maven-native-pg/`](../../ekbatan-examples/quarkus-wallet-rest-maven-native-pg) / [`-mariadb`](../../ekbatan-examples/quarkus-wallet-rest-maven-native-mariadb) / [`-mysql`](../../ekbatan-examples/quarkus-wallet-rest-maven-native-mysql) — GraalVM native-image variants

**Micronaut** (`micronaut-parent` + `micronaut-maven-plugin`):

- [`micronaut-wallet-rest-maven-pg/`](../../ekbatan-examples/micronaut-wallet-rest-maven-pg) — PostgreSQL (demonstrates `<annotationProcessorPaths combine.children="append">` to extend the Micronaut parent POM's AP list)
- [`micronaut-wallet-rest-maven-mariadb/`](../../ekbatan-examples/micronaut-wallet-rest-maven-mariadb) — MariaDB
- [`micronaut-wallet-rest-maven-mysql/`](../../ekbatan-examples/micronaut-wallet-rest-maven-mysql) — MySQL
- [`micronaut-wallet-rest-maven-native-pg/`](../../ekbatan-examples/micronaut-wallet-rest-maven-native-pg) / [`-mariadb`](../../ekbatan-examples/micronaut-wallet-rest-maven-native-mariadb) / [`-mysql`](../../ekbatan-examples/micronaut-wallet-rest-maven-native-mysql) — GraalVM native-image variants; build via `./mvnw package -Dpackaging=native-image`

← Back to [docs index](../README.md) · [Top README](../../README.md)
