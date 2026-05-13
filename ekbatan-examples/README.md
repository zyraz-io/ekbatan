# Ekbatan examples

Each subdirectory here is a **standalone** runnable project that uses Ekbatan as a Maven Central dependency — they are intentionally *not* part of the parent Gradle multi-project build. Clone the repo, `cd` into one, and you can build and run it on its own.

| Example | Stack | Demonstrates |
|---|---|---|
| [`spring-boot-wallet-rest/`](./spring-boot-wallet-rest) | Spring Boot 4 + Java 25 + Postgres | The minimal JVM-only baseline: `Model` + `Entity` + 4 `Action`s + listen-to-yourself `EventHandler` + REST endpoints + Testcontainers integration test. Start here. |
| [`spring-boot-wallet-rest-native/`](./spring-boot-wallet-rest-native) | Spring Boot 4 + Java 25 + Postgres + GraalVM | The same app, packaged for GraalVM native-image. Adds the `org.graalvm.buildtools.native` plugin, the `ekbatan-native` dependency with `FlywayHelper`, and the small AOT-time guard required because Spring AOT runs `main()` at build time. |
| [`quarkus-wallet-rest/`](./quarkus-wallet-rest) | Quarkus 3.34 + Java 25 + **MariaDB** | Same domain, on Quarkus's CDI + JAX-RS surface, backed by MariaDB instead of Postgres. Highlights the cross-dialect differences in one place: `JSON` vs `JSONB`, `DATETIME(6)` vs `TIMESTAMP`, no partial indexes, `eventlog` as a separate database, native `UUID` type. |
| [`quarkus-wallet-rest-native/`](./quarkus-wallet-rest-native) | Quarkus 3.34 + Java 25 + MariaDB + GraalVM | The same app, configured for GraalVM native-image. Adds the `ekbatan-native` dependency, `FlywayHelper`, a `src/integrationTest/` source set with a `@QuarkusIntegrationTest`, and `quarkus-jdbc-mariadb` for JDBC driver native registration. **Native binary builds; `testNative` is currently blocked on framework-side Flyway-SPI + HikariCP-7 native gaps** — see the project's README for details. |

## Conventions

- **Standalone Gradle build.** Each example has its own `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, and gradle wrapper.
- **Docker required.** Tests use Testcontainers; `bootRun` uses Spring Boot's docker-compose integration (or the equivalent for non-Spring frameworks); the JOOQ codegen plugin spins up its own throwaway container at build time. One Docker prerequisite, three uses.
- **Pulls the published Ekbatan version** from Maven Central — pinned in each example's `gradle.properties` as `ekbatanVersion`. The parent repo's local source changes are not visible to these examples until they are released to Central.
- **Naming.** Sub-folder names describe the framework + style of the example (e.g. `spring-boot-wallet-rest`, future `quarkus-wallet-rest`, `plain-java-wallet-cli`, etc.) so the directory listing acts as a menu.
