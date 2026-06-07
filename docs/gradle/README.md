# Gradle

What you write in `build.gradle.kts` to consume Ekbatan, generate jOOQ classes from your Flyway migrations, and ship a runnable application. Many examples use Gradle, but the framework's JARs work with any JVM build tool; the codegen plugin ([`dev.monosoul.jooq-docker`](https://github.com/monosoul/jooq-gradle-plugin)) is the piece that's Gradle-specific.

For the Maven equivalent, see [Maven](../maven/README.md). The framework itself is build-tool-agnostic — only the codegen tooling differs.

- **[Getting started with Gradle](getting-started.md)** — per-stack dependency blocks (Spring Boot / Quarkus / Micronaut / plain Java), annotation processor wiring, the `-parameters` compiler flag, Spring Boot BOM override for jOOQ, optional add-ons, build commands.
- **[jOOQ codegen on Gradle](jooq-codegen.md)** — the `dev.monosoul.jooq-docker` plugin in detail, per-dialect `build.gradle.kts` blocks for PostgreSQL / MariaDB / MySQL, the `jooq { withContainer { … } }` config, `generateJooqClasses` task, ForcedTypes, source-set wiring.

For the framework-level concepts these pages assume you already know, see [docs/wiring/](../wiring/) (DI + auto-config) and [docs/database/](../database/README.md) (the canonical schema and dialect notes — build-tool agnostic).

Runnable Gradle references live across `ekbatan-examples/`:

- [`spring-boot-wallet-rest-gradle-pg`](../../ekbatan-examples/spring-boot-wallet-rest-gradle-pg) — Spring Boot + PostgreSQL
- [`spring-boot-wallet-rest-gradle-mysql`](../../ekbatan-examples/spring-boot-wallet-rest-gradle-mysql) — Spring Boot + MySQL (with `UuidStringConverter`)
- [`quarkus-wallet-rest-gradle-mariadb`](../../ekbatan-examples/quarkus-wallet-rest-gradle-mariadb) — Quarkus + MariaDB
- [`micronaut-wallet-rest-gradle-pg`](../../ekbatan-examples/micronaut-wallet-rest-gradle-pg) — Micronaut + PostgreSQL
- [`spring-boot-wallet-rest-gradle-native-pg`](../../ekbatan-examples/spring-boot-wallet-rest-gradle-native-pg) — adds GraalVM native-image

← Back to [docs index](../README.md) · [Top README](../../README.md)
