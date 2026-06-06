# Ekbatan examples

Each subdirectory here is a **standalone** runnable project that uses Ekbatan as a Maven Central dependency — they are intentionally *not* part of the parent Gradle multi-project build. Clone the repo, `cd` into one, and you can build and run it on its own.

The base layout is a uniform **3 × 2 × 3 grid** — every DI framework × build tool × dialect combination — plus a native-image variant for each. Every base project demonstrates the same surface: `Model` + `Entity` + 4 `Action`s + listen-to-yourself `EventHandler` + caller-side `KeyedLockProvider` + REST endpoints + Testcontainers integration test. Pick by stack and dialect; the framework wiring is consistent.

## The grid: 36 wallet REST projects (3 stacks × 2 build tools × 3 dialects, JVM + native)

### Spring Boot

| Build | PostgreSQL | MariaDB | MySQL |
|---|---|---|---|
| Gradle | [`spring-boot-wallet-rest-gradle-pg`](./spring-boot-wallet-rest-gradle-pg) | [`spring-boot-wallet-rest-gradle-mariadb`](./spring-boot-wallet-rest-gradle-mariadb) | [`spring-boot-wallet-rest-gradle-mysql`](./spring-boot-wallet-rest-gradle-mysql) |
| Gradle + native | [`spring-boot-wallet-rest-gradle-native-pg`](./spring-boot-wallet-rest-gradle-native-pg) | [`spring-boot-wallet-rest-gradle-native-mariadb`](./spring-boot-wallet-rest-gradle-native-mariadb) | [`spring-boot-wallet-rest-gradle-native-mysql`](./spring-boot-wallet-rest-gradle-native-mysql) |
| Maven | [`spring-boot-wallet-rest-maven-pg`](./spring-boot-wallet-rest-maven-pg) | [`spring-boot-wallet-rest-maven-mariadb`](./spring-boot-wallet-rest-maven-mariadb) | [`spring-boot-wallet-rest-maven-mysql`](./spring-boot-wallet-rest-maven-mysql) |
| Maven + native | [`spring-boot-wallet-rest-maven-native-pg`](./spring-boot-wallet-rest-maven-native-pg) | [`spring-boot-wallet-rest-maven-native-mariadb`](./spring-boot-wallet-rest-maven-native-mariadb) | [`spring-boot-wallet-rest-maven-native-mysql`](./spring-boot-wallet-rest-maven-native-mysql) |

### Quarkus

| Build | PostgreSQL | MariaDB | MySQL |
|---|---|---|---|
| Gradle | [`quarkus-wallet-rest-gradle-pg`](./quarkus-wallet-rest-gradle-pg) | [`quarkus-wallet-rest-gradle-mariadb`](./quarkus-wallet-rest-gradle-mariadb) | [`quarkus-wallet-rest-gradle-mysql`](./quarkus-wallet-rest-gradle-mysql) |
| Gradle + native | [`quarkus-wallet-rest-gradle-native-pg`](./quarkus-wallet-rest-gradle-native-pg) | [`quarkus-wallet-rest-gradle-native-mariadb`](./quarkus-wallet-rest-gradle-native-mariadb) | [`quarkus-wallet-rest-gradle-native-mysql`](./quarkus-wallet-rest-gradle-native-mysql) |
| Maven | [`quarkus-wallet-rest-maven-pg`](./quarkus-wallet-rest-maven-pg) | [`quarkus-wallet-rest-maven-mariadb`](./quarkus-wallet-rest-maven-mariadb) | [`quarkus-wallet-rest-maven-mysql`](./quarkus-wallet-rest-maven-mysql) |
| Maven + native | [`quarkus-wallet-rest-maven-native-pg`](./quarkus-wallet-rest-maven-native-pg) | [`quarkus-wallet-rest-maven-native-mariadb`](./quarkus-wallet-rest-maven-native-mariadb) | [`quarkus-wallet-rest-maven-native-mysql`](./quarkus-wallet-rest-maven-native-mysql) |

### Micronaut

| Build | PostgreSQL | MariaDB | MySQL |
|---|---|---|---|
| Gradle | [`micronaut-wallet-rest-gradle-pg`](./micronaut-wallet-rest-gradle-pg) | [`micronaut-wallet-rest-gradle-mariadb`](./micronaut-wallet-rest-gradle-mariadb) | [`micronaut-wallet-rest-gradle-mysql`](./micronaut-wallet-rest-gradle-mysql) |
| Gradle + native | [`micronaut-wallet-rest-gradle-native-pg`](./micronaut-wallet-rest-gradle-native-pg) | [`micronaut-wallet-rest-gradle-native-mariadb`](./micronaut-wallet-rest-gradle-native-mariadb) | [`micronaut-wallet-rest-gradle-native-mysql`](./micronaut-wallet-rest-gradle-native-mysql) |
| Maven | [`micronaut-wallet-rest-maven-pg`](./micronaut-wallet-rest-maven-pg) | [`micronaut-wallet-rest-maven-mariadb`](./micronaut-wallet-rest-maven-mariadb) | [`micronaut-wallet-rest-maven-mysql`](./micronaut-wallet-rest-maven-mysql) |
| Maven + native | [`micronaut-wallet-rest-maven-native-pg`](./micronaut-wallet-rest-maven-native-pg) | [`micronaut-wallet-rest-maven-native-mariadb`](./micronaut-wallet-rest-maven-native-mariadb) | [`micronaut-wallet-rest-maven-native-mysql`](./micronaut-wallet-rest-maven-native-mysql) |

## What every base project demonstrates

| Surface | Spring Boot | Quarkus | Micronaut |
|---|---|---|---|
| HTTP layer | `@RestController WalletController` | `@Path WalletResource` (JAX-RS) | `@Controller WalletController` |
| DI of beans | `@Configuration` + `@Bean` | `@ApplicationScoped` + `@Produces` | `@Factory` + `@Singleton` method |
| Framework auto-discovery | classpath scan + AOT | Jandex at deployment | compile-time `EkbatanStereotypeVisitor` |
| Flyway integration | **`spring-boot-starter-flyway`** + `@FlywayDataSource @Bean DataSource` from `ShardingConfig` | **`quarkus-flyway`** + `EkbatanShardFlywayCustomizer` implementing `FlywayConfigurationCustomizer` (CDI) | **`micronaut-flyway`** + `EkbatanShardFlywayCustomizer @Named("default")` implementing `FlywayConfigurationCustomizer` |
| HTTP serialization | `spring-boot-starter-web` (Jackson 2 via auto-config) | `quarkus-rest-jackson` (→ pulls `quarkus-jackson`) | `micronaut-serde-jackson` + `@Serdeable` on DTOs (compile-time serdes; no reflection) |
| Integration test | `@SpringBootTest` + `@TestConfiguration` | `@QuarkusTest` + `@QuarkusTestResource` | `@MicronautTest` + `TestPropertyProvider` |

> **Each wallet uses the framework-native Flyway extension** — not raw `flyway-core` and a hand-rolled migration runner. The customizer is what feeds connection coordinates from `ekbatan.sharding.groups[0].members[0].configs.primaryConfig.*` into Flyway, keeping a single source of truth instead of duplicating credentials in `spring.datasource.*` / `quarkus.datasource.*` / `flyway.datasources.default.*`. See the wiring docs for the exact dep coordinates and customizer shape per framework: [Spring Boot](../docs/wiring/spring.md#flyway--use-spring-boot-starter-flyway--a-flywaydatasource-bean) · [Quarkus](../docs/wiring/quarkus.md#flyway--use-quarkus-flyway--a-flywayconfigurationcustomizer) · [Micronaut](../docs/wiring/micronaut.md#flyway--use-micronaut-flyway--a-flywayconfigurationcustomizer).
>
> The opposite pattern (raw Flyway + `FlywayHelper.migrate(...)`) is what the **framework's own integration tests** under `ekbatan-integration-tests/` do — because they exercise `ekbatan-core` directly without a DI framework. Don't copy that into your app; copy from the wallet examples here instead. See [docs/runtime/native-image.md § two patterns](../docs/runtime/native-image.md#flyway-on-native--two-patterns).

Across every project:

- **Domain code is identical** — `Wallet` `Model`, `Notification` `Entity`, 4 `@EkbatanAction`s, `WalletMoneyDepositedEventHandler`, 2 `@EkbatanRepository` classes.
- **Caller-side `KeyedLockProvider`** wired per `(DI × dialect)` — Spring/`@Configuration`, Quarkus/`@ApplicationScoped`, Micronaut/`@Factory`; PG/MariaDB/MySQL providers — and the deposit endpoint is wrapped in a `try (var lease = lockProvider.acquire(…))` lease that spans both `Action.perform()` and the framework's transaction commit.
- **Listen-to-yourself** — the deposit emits a `WalletMoneyDepositedEvent`, the in-process handler picks it up and creates a `Notification` row. Tests use Awaitility to assert the notification appears.
- **`lockConfig` slot** in `application.yml`/`application.properties` — separate Hikari pool sized for max concurrent held leases.

The **native-image variants** (Gradle + Maven, all stacks/dialects) add the `ekbatan-native` dependency (for Jackson 3 record reflection metadata + vendored HikariCP RMR) and the `-Dio.ekbatan.graalvm.scan.packages=io.ekbatan,io.example` build arg. They do **not** swap to `FlywayHelper` — the framework Flyway extensions ship their own substrate-VM resource scanning, so the JVM and native code paths are identical at the source level. `testNative` (Gradle) / `mvn test -PnativeTest` / `mvn test -Pnative` / `quarkusIntTest` (depending on framework) runs the full `WalletControllerIntegrationTest` suite against the compiled native binary.

## Specialized examples (not in the grid — these demonstrate specific patterns)

| Example | Stack | Demonstrates |
|---|---|---|
| [`spring-boot-job-worker-gradle-pg`](./spring-boot-job-worker-gradle-pg) | Spring Boot + Postgres, **no HTTP** | `@EkbatanDistributedJob` as primary feature. `spring-boot-starter-web` is dropped, `spring.main.web-application-type=none`. Two jobs run end-to-end: `WalletStipendJob` (every 2s, deposits to wallets with `balance < 100`) and `WalletReportJob` (every 5s, read-only count+sum). The job → action → outbox → handler → notification chain runs inside one worker JVM. |
| [`spring-boot-wallet-rest-gradle-sharded-pg`](./spring-boot-wallet-rest-gradle-sharded-pg) | Spring Boot + **2× Postgres** | Sharding. `Wallet` uses `ShardedId`, `WalletRepository` uses `EmbeddedBitsShardingStrategy`, two Postgres containers stand in for two policy-axis shards (global + mexico). `WalletTransferAction` is an `allowCrossShard(true)` mechanics demo and runs two independent transactions, one per shard; use the saga example for production transfer workflows. |
| [`spring-boot-wallet-saga-gradle-pg`](./spring-boot-wallet-saga-gradle-pg) | Spring Boot + Postgres | Saga pattern. A wallet-to-wallet transfer is decomposed into 3 actions (`InitiateTransferAction` → `CompleteTransferAction` → `RefundTransferAction` on failure) chained by `@EkbatanEventHandler`s. Forward-only compensation: when the destination is closed/missing, a refund action credits the source back. |

## Conventions

- **Standalone build.** Each example has its own wrapper and build-tool config (`build.gradle.kts` + `settings.gradle.kts` + `gradle.properties` + `gradlew` for the Gradle examples; `pom.xml` + `mvnw` for the Maven ones).
- **Docker required.** Tests use Testcontainers; `bootRun` / `quarkus:dev` / `mn:run` uses the corresponding integration (or you bring up `compose.yaml` manually for Micronaut/Quarkus, which lack auto-startup); the jOOQ codegen step spins up its own throwaway container at build time. One Docker prerequisite, three uses.
- **Naming.** `[stack]-wallet-rest-[gradle|maven][-native?]-[pg|mariadb|mysql]` — predictable from any starting point.
