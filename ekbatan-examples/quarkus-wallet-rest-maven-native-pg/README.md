# quarkus-wallet-rest-maven-native-pg

A standalone Quarkus example that uses Ekbatan from Maven Central, backed by **PostgreSQL**. The PG dialect of the Maven Quarkus triple — siblings are [`quarkus-wallet-rest-maven-mariadb`](../quarkus-wallet-rest-maven-mariadb) and [`quarkus-wallet-rest-maven-mysql`](../quarkus-wallet-rest-maven-mysql). Same domain as the Gradle counterpart [`quarkus-wallet-rest-gradle-mariadb`](../quarkus-wallet-rest-gradle-mariadb) (which is MariaDB-backed); only build descriptor and dialect differ.

## What it shows

| Surface | Class |
|---|---|
| `Model` (event-emitting) | `Wallet` |
| `Entity` (no events) | `Notification` |
| `Action` | `WalletCreateAction`, `WalletDepositMoneyAction`, `WalletCloseAction`, `CreateNotificationAction` |
| `EventHandler` | `WalletMoneyDepositedEventHandler` |
| `Repository` | `WalletRepository`, `NotificationRepository` |
| REST | `WalletResource` (JAX-RS `@Path`) |
| Flyway migration | `EkbatanShardFlywayMigrator` — observes `StartupEvent` with `@Priority(Interceptor.Priority.PLATFORM_BEFORE)` and calls `FlywayMigrator.migrate(shardingConfig)` |
| Integration test | `WalletResourceIntegrationTest` (`@QuarkusTest` + `@QuarkusTestResource(PostgresTestResource.class)` + RestAssured) |

The framework wiring (the listen-to-yourself path, the outbox guarantees) is identical to the Gradle Quarkus sibling — see [`quarkus-wallet-rest-gradle-mariadb`](../quarkus-wallet-rest-gradle-mariadb) and the [Wiring with Quarkus](../../docs/wiring/quarkus.md) doc.

## Quarkus-on-Maven specifics

Quarkus has first-class Maven support — the only Maven-specific bits are the build descriptor itself. No parent POM (Quarkus imports `quarkus-bom` instead). No `combine.children="append"` gymnastics on `<annotationProcessorPaths>` (unlike Micronaut). The Quarkus Maven plugin handles `mn:run` analog (`quarkus:dev`) and `package` (produces `target/quarkus-app/`).

### What's different from the Spring Boot / Micronaut Maven siblings

| Concern | Spring Boot Maven | Quarkus Maven (this project) |
|---|---|---|
| Parent POM | `spring-boot-starter-parent` | none; `quarkus-bom` import in `<dependencyManagement>` |
| Build plugin | `spring-boot-maven-plugin` | `quarkus-maven-plugin` (with `<extensions>true</extensions>`) |
| Config file | `application.yml` | `application.properties` |
| Dev mode | `./mvnw spring-boot:run` | `./mvnw quarkus:dev` |
| Bootstrap class | `Application.java` with `SpringApplication.run(...)` | none; Quarkus generates the main class at build time |
| Flyway runner | `@Bean` factory method on a `@Configuration` class | `@ApplicationScoped` bean with `@Observes StartupEvent` (CDI) |
| HTTP layer | Spring MVC `@RestController` | JAX-RS `@Path`/`@POST`/`@GET` on a `@Path`-annotated class |
| Test harness | `@SpringBootTest` + `@TestConfiguration` for testcontainer wiring | `@QuarkusTest` + `@QuarkusTestResource(...)` (a `QuarkusTestResourceLifecycleManager` that starts the container BEFORE the Quarkus context builds) |
| HTTP test client | Spring's `TestRestTemplate` / JDK `HttpClient` | RestAssured |

### Why `<extensions>true</extensions>` on the Quarkus plugin

Tells Maven to consult the plugin for additional lifecycle bindings — without it, the Quarkus plugin can't register its `generate-code` / `generate-code-tests` / `build` goals into the `generate-sources` / `package` phases, and `./mvnw package` produces a plain JAR instead of a `quarkus-app/` directory.

### Why the driver dependency is *compile-scoped*, not `runtime`

Spring Boot lets you `<scope>runtime</scope>` the JDBC driver because the framework lazily resolves the driver class via `Class.forName(...)` at first connection. Quarkus' build-time augmentation processes the driver classes during the `generate-code` build step, so the driver must be on the compile classpath. Marking the Postgres driver as compile-scoped is the safe choice on Quarkus.

## How the jOOQ codegen works on Maven

Identical to the Spring Boot / Micronaut Maven siblings — three plugins (fabric8 `docker-maven-plugin`, `flyway-maven-plugin`, `jooq-codegen-maven`) chained on `initialize` / `generate-sources` / `prepare-package`. The Quarkus Maven plugin and jOOQ plugin both run in `generate-sources`; Maven runs same-phase plugins in declaration order in the POM. See [docs/maven/jooq-codegen.md](../../docs/maven/jooq-codegen.md) for the full chain reference.

The PG `<forcedType>` blocks match the Gradle examples exactly — `InstantConverter` for `TIMESTAMP`, `JSONBObjectNodeConverter` for `JSONB`. Postgres's native `UUID` type needs no converter.

## What changes between the three Maven Quarkus siblings

Same `pom.xml` chain, same Quarkus wiring, same Java domain code — the dialect deltas are concentrated in three places:

| Concern | PG (this project) | MariaDB sibling | MySQL sibling |
|---|---|---|---|
| Migration types | `UUID`, `JSONB`, `TIMESTAMP`, partial indexes | `UUID` (native, 10.7+), `JSON`, `DATETIME(6)`, no partial indexes | `CHAR(36) CHARACTER SET ascii`, `JSON`, `DATETIME(6)`, no partial indexes |
| `eventlog` | a *schema* inside the main DB; created by `V0001` | a separate *database*; created by `V0000`; needs `mariadb_init.sql` GRANT | a separate *database*; created by `V0000`; needs `mysql_init.sql` GRANT |
| `<forcedType>` entries | `InstantConverter`, `JSONBObjectNodeConverter` | `InstantConverter`, `JSONObjectNodeConverter` | `InstantConverter`, `JSONObjectNodeConverter`, **plus** `UuidStringConverter` (for `CHAR(36)` → `UUID`) |
| Generated package | `…default_schema.tables.*` / `…eventlog_schema.tables.*` | `…tables.*` (single-database codegen) | `…tables.*` (single-database codegen) |

## Run locally

You need Docker installed. The wrapper handles Maven itself.

```bash
docker compose up -d
./mvnw quarkus:dev
```

The API is at `http://localhost:8080/wallets`. Quarkus dev mode hot-reloads on source changes — edit a class and the next request picks up the new code.

```bash
# Create
curl -X POST http://localhost:8080/wallets \
    -H 'Content-Type: application/json' \
    -d '{"ownerId":"00000000-0000-0000-0000-000000000001","currency":"USD","initialBalance":"0.00"}'

# Deposit
curl -X POST http://localhost:8080/wallets/<id>/deposits \
    -H 'Content-Type: application/json' \
    -d '{"amount":"25.50","recipient":"alice@example.com"}'

# Read
curl http://localhost:8080/wallets/<id>

# Close
curl -X POST http://localhost:8080/wallets/<id>/close
```

When you're done:

```bash
docker compose down
```

## Test

```bash
./mvnw verify
```

The integration test boots Quarkus with `@QuarkusTestResource(PostgresTestResource.class)`. The resource manager starts a Testcontainers `PostgreSQLContainer`, runs Flyway migrations on it, and publishes the container's JDBC URL as runtime SmallRye Config properties — all *before* the Quarkus app context builds. RestAssured then hits each REST endpoint and Awaitility polls the `Notification` table until the listen-to-yourself handler creates the expected row.

## Maven-specific notes

- **`-parameters` compiler flag** — same as the Spring / Micronaut siblings. Ekbatan reads parameter names via reflection (for `@AutoBuilder`-generated builders + Jackson 3 record deserialization).
- **`mavenLocal` is enabled** — the POM lists `~/.m2/repository` ahead of Maven Central, so an in-progress local Ekbatan build (`./gradlew publishToMavenLocal` in the parent repo) takes precedence over the published version.
- **jOOQ version override** — Quarkus' BOM doesn't pin jOOQ, but the codegen plugin's default may not match what Ekbatan was built against. The `<jooq.version>` property pins it explicitly.
- **Spotless** — wired with Palantir Java Format 2.81.0, matching the framework's Gradle setup. `./mvnw spotless:check` (runs during `compile`) fails on drift; `./mvnw spotless:apply` rewrites files in place.

## See also

- [`quarkus-wallet-rest-maven-mariadb`](../quarkus-wallet-rest-maven-mariadb) — the MariaDB sibling
- [`quarkus-wallet-rest-maven-mysql`](../quarkus-wallet-rest-maven-mysql) — the MySQL sibling
- [`quarkus-wallet-rest-gradle-mariadb`](../quarkus-wallet-rest-gradle-mariadb) — the Gradle counterpart (MariaDB-backed)
- [`spring-boot-wallet-rest-maven-pg`](../spring-boot-wallet-rest-maven-pg) — the Spring Boot Maven + PG counterpart
- [`micronaut-wallet-rest-maven-pg`](../micronaut-wallet-rest-maven-pg) — the Micronaut Maven + PG counterpart
- [Ekbatan docs › Wiring with Quarkus](../../docs/wiring/quarkus.md)
- [Ekbatan docs › Database › PostgreSQL](../../docs/database/postgresql.md) — column types, converters, framework tables
- [Ekbatan docs › JOOQ codegen on Maven](../../docs/maven/jooq-codegen.md) — the codegen chain in detail
- [Ekbatan docs › Getting started with Maven](../../docs/maven/getting-started.md) — per-stack POM walkthroughs (Spring / Quarkus / Micronaut / plain Java)
