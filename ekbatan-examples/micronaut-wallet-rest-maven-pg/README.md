# micronaut-wallet-rest-maven-pg

A standalone Micronaut example that uses Ekbatan from Maven Central, backed by **PostgreSQL**. The PG dialect of the Maven Micronaut triple — siblings are [`micronaut-wallet-rest-maven-mariadb`](../micronaut-wallet-rest-maven-mariadb) and [`micronaut-wallet-rest-maven-mysql`](../micronaut-wallet-rest-maven-mysql). Same domain and same wiring as the Gradle counterpart [`micronaut-wallet-rest-gradle-pg`](../micronaut-wallet-rest-gradle-pg); only `pom.xml` vs. `build.gradle.kts` differs.

## What it shows

| Surface | Class |
|---|---|
| `Model` (event-emitting) | `Wallet` |
| `Entity` (no events) | `Notification` |
| `Action` | `WalletCreateAction`, `WalletDepositMoneyAction`, `WalletCloseAction`, `CreateNotificationAction` |
| `EventHandler` | `WalletMoneyDepositedEventHandler` |
| `Repository` | `WalletRepository`, `NotificationRepository` |
| REST | `WalletController` (Micronaut `@Controller`) |
| Flyway migration | `FlywayConfiguration` — `@Context @Singleton @PostConstruct`, eager-initialized at context build time |
| Integration test | `WalletControllerIntegrationTest` (`@MicronautTest` + `TestPropertyProvider` + Testcontainers) |

The framework wiring (the listen-to-yourself path, the outbox guarantees, etc.) is identical to the Gradle sibling — see [`micronaut-wallet-rest-gradle-pg`](../micronaut-wallet-rest-gradle-pg) and the [Wiring with Micronaut](../../docs/wiring/micronaut.md) doc.

## Micronaut-on-Maven specifics

The Micronaut Maven plugin and the Micronaut parent POM handle most of the wiring, but two things in `pom.xml` are worth pointing out:

### (1) `<annotationProcessorPaths combine.children="append">`

The Micronaut parent POM already pre-configures `maven-compiler-plugin` with `micronaut-inject-java` on `<annotationProcessorPaths>`. We **append** two more entries (Ekbatan's AP jar and `ekbatan-micronaut`) using `combine.children="append"` — without that flag, Maven config merging *replaces* the parent's list and Micronaut's own processor stops running. The downstream symptoms range from "no `BeanDefinition` for `WalletController`" at boot to silent missing routes.

```xml
<annotationProcessorPaths combine.children="append">
    <path>
        <groupId>io.github.zyraz-io</groupId>
        <artifactId>ekbatan-annotation-processor</artifactId>
        <version>${ekbatan.version}</version>
    </path>
    <path>
        <groupId>io.github.zyraz-io</groupId>
        <artifactId>ekbatan-micronaut</artifactId>
        <version>${ekbatan.version}</version>
    </path>
</annotationProcessorPaths>
```

`ekbatan-micronaut` carries the `EkbatanStereotypeVisitor` — without it on the AP path, `@EkbatanAction` / `@EkbatanRepository` / `@EkbatanEventHandler` classes never get lifted to `@Singleton` and Micronaut treats them as plain non-beans. Runtime fails with `UnsatisfiedDependencyException` on the first `ActionExecutor.execute(...)` call.

### (2) `-Amicronaut.processing.annotations=io.example.wallet.*`

Tells Micronaut's AP where to scan for *our* beans (in addition to the default packages). Gradle's equivalent is `micronaut { processing { annotations("io.example.wallet.*") } }`. Without this, classes in `io.example.wallet.*` that aren't annotated with stock Micronaut stereotypes (our `@EkbatanAction` etc., which the visitor lifts to `@Singleton` at compile time) get silently skipped.

```xml
<compilerArgs>
    <arg>-Amicronaut.processing.annotations=io.example.wallet.*</arg>
    <arg>-Amicronaut.processing.incremental=true</arg>
</compilerArgs>
```

## How the jOOQ codegen works on Maven

Identical to the Spring-Boot-on-Maven siblings — three plugins composed in the lifecycle: fabric8 `docker-maven-plugin` (start container), `flyway-maven-plugin` (migrate), `jooq-codegen-maven` (generate Java). On Gradle we'd use one plugin (`dev.monosoul.jooq-docker`) that bundles all three. See [docs/maven/jooq-codegen.md](../../docs/maven/jooq-codegen.md) for the full reference.

The jOOQ `<forcedType>` blocks match the Gradle sibling exactly — `InstantConverter` for `TIMESTAMP`, `JSONBObjectNodeConverter` for `JSONB`. Postgres's native `UUID` type needs no converter.

## What changes between the three Maven Micronaut siblings

Same `pom.xml` chain, same Micronaut wiring, same Java domain code — the dialect deltas are concentrated in three places:

| Concern | PG (this project) | MariaDB sibling | MySQL sibling |
|---|---|---|---|
| Migration types | `UUID`, `JSONB`, `TIMESTAMP`, partial indexes | `UUID` (native, 10.7+), `JSON`, `DATETIME(6)`, no partial indexes | `CHAR(36) CHARACTER SET ascii`, `JSON`, `DATETIME(6)`, no partial indexes |
| `eventlog` | a *schema* inside the main DB; created by `V0001` | a separate *database*; created by `V0000`; needs `mariadb_init.sql` GRANT | a separate *database*; created by `V0000`; needs `mysql_init.sql` GRANT |
| `<forcedType>` entries | `InstantConverter`, `JSONBObjectNodeConverter` | `InstantConverter`, `JSONObjectNodeConverter` | `InstantConverter`, `JSONObjectNodeConverter`, **plus** `UuidStringConverter` (for `CHAR(36)` → `UUID`) |
| Generated package | `…default_schema.tables.*` / `…eventlog_schema.tables.*` | `…tables.*` (single-database codegen) | `…tables.*` (single-database codegen) |

## Run locally

You need Docker installed and the wrapper handles Maven itself.

```bash
# Start Postgres (Micronaut has no equivalent of Spring Boot's docker-compose integration,
# so the container has to come up explicitly):
docker compose up -d

# Then run the app:
./mvnw mn:run
```

The API is at `http://localhost:8080/wallets`.

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

The integration test boots Micronaut with a Testcontainers PostgreSQL via `TestPropertyProvider.getProperties()`, runs migrations through the `@Context`-eager `FlywayConfiguration`, hits each endpoint, and asserts that the `Wallet` is updated and the `Notification` row eventually appears (the handler is async, so we use Awaitility to poll up to 15 seconds).

## Maven-specific notes

- **`-parameters` compiler flag** — same as the Spring sibling. Ekbatan reads parameter names via reflection (for `@AutoBuilder`-generated builders + Jackson 3 record deserialization).
- **`mavenLocal` is enabled** — the POM lists `~/.m2/repository` ahead of Maven Central, so an in-progress local Ekbatan build (`./gradlew publishToMavenLocal` in the parent repo) takes precedence over the published version.
- **No jOOQ version override needed** — Micronaut's BOM doesn't pin jOOQ, so the codegen plugin's 3.20.x default takes effect for both the generated classes and the runtime.
- **Spotless** — wired with Palantir Java Format 2.81.0, matching the framework repo. `./mvnw spotless:check` (also runs during `compile`) fails on drift; `./mvnw spotless:apply` rewrites files in place.

## See also

- [`micronaut-wallet-rest-maven-mariadb`](../micronaut-wallet-rest-maven-mariadb) — the MariaDB sibling
- [`micronaut-wallet-rest-maven-mysql`](../micronaut-wallet-rest-maven-mysql) — the MySQL sibling
- [`micronaut-wallet-rest-gradle-pg`](../micronaut-wallet-rest-gradle-pg) — the Gradle counterpart, same source code, different build tool
- [`spring-boot-wallet-rest-maven-pg`](../spring-boot-wallet-rest-maven-pg) — the Spring Boot Maven counterpart
- [Ekbatan docs › Wiring with Micronaut](../../docs/wiring/micronaut.md)
- [Ekbatan docs › Database › PostgreSQL](../../docs/database/postgresql.md) — column types, converters, framework tables
- [Ekbatan docs › JOOQ codegen on Maven](../../docs/maven/jooq-codegen.md) — the codegen chain in detail
- [Ekbatan docs › Getting started with Maven](../../docs/maven/getting-started.md) — per-stack POM walkthroughs (Spring / Quarkus / **Micronaut** / plain Java)
