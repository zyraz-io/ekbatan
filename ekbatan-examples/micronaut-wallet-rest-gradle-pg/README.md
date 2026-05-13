# micronaut-wallet-rest-gradle-pg

A standalone Micronaut example that uses Ekbatan from Maven Central — same domain and same wiring as [`spring-boot-wallet-rest-gradle-pg`](../spring-boot-wallet-rest-gradle-pg), just on Micronaut's DI + JAX-RS-style HTTP surface. The native-image variant lives in a sibling project ([`micronaut-wallet-rest-gradle-native-pg`](../micronaut-wallet-rest-gradle-native-pg)).

## What it shows

| Surface | Class |
|---|---|
| `Model` (event-emitting) | `Wallet` |
| `Entity` (no events) | `Notification` |
| `Action` | `WalletCreateAction`, `WalletDepositMoneyAction`, `WalletCloseAction`, `CreateNotificationAction` |
| `EventHandler` | `WalletMoneyDepositedEventHandler` |
| `Repository` | `WalletRepository`, `NotificationRepository` |
| REST | `WalletController` (Micronaut `@Controller` + `@Get` / `@Post`) |
| Flyway migration | `FlywayConfiguration` — `@Singleton @Context` runs Flyway at context build time |
| Integration test | `WalletControllerIntegrationTest` — `@MicronautTest` + `TestPropertyProvider` + Testcontainers |

The listen-to-yourself path (the deposit → outbox → handler → notification flow) is identical to the Spring sibling — see that project's [README](../spring-boot-wallet-rest-gradle-pg/README.md#how-the-listen-to-yourself-path-lands) for the walkthrough. The action / handler / repository code is byte-for-byte identical.

## Micronaut-specific bits

The framework is framework-agnostic; only the wiring surface differs.

- **`@Context` over `@Singleton`** for `FlywayConfiguration` — `@Context` makes the bean eagerly initialized at context build time, which means migrations run before any `StartupEvent` listener fires (and the framework's `JobRegistry` starts on `StartupEvent`, so `scheduled_tasks` is already there when db-scheduler polls).
- **`@MicronautTest` + `TestPropertyProvider`** for the integration test — the property provider runs *before* the application context is built, so Testcontainers comes up first and the dynamic JDBC URL lands in `ekbatan.sharding.*` before any Ekbatan bean reads it.
- **`driverClassName` is set explicitly** in `application.yml` — Micronaut's Hikari init in this combo doesn't always pick the Postgres driver via the JDBC SPI when launched from the Gradle test worker.
- **Compile-time bean lifting** — Ekbatan's `EkbatanStereotypeVisitor` (loaded via `annotationProcessor("io.github.zyraz-io:ekbatan-micronaut")`) lifts every `@EkbatanAction` / `@EkbatanRepository` / `@EkbatanEventHandler` to `@Singleton` so Micronaut emits `BeanDefinition`s for them at AOT time. Without this AP entry, the annotated classes never become beans.

## Run locally

```bash
docker compose -f compose.yaml up -d
./gradlew run
```

Micronaut doesn't have Spring Boot's `docker-compose` integration plugin, so the container needs to come up manually. The API is at `http://localhost:8080/wallets`.

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

When you're done: `docker compose -f compose.yaml down`.

## Test

```bash
./gradlew test
```

The integration test boots a Testcontainers PostgreSQL via `TestPropertyProvider`, runs migrations through `FlywayConfiguration`'s `@PostConstruct`, hits each endpoint, and asserts that the `Wallet` is updated and the `Notification` row eventually appears (the handler is async, so Awaitility polls up to 15 seconds).

## See also

- [`micronaut-wallet-rest-gradle-native-pg`](../micronaut-wallet-rest-gradle-native-pg) — the same app packaged for GraalVM native-image.
- [`spring-boot-wallet-rest-gradle-pg`](../spring-boot-wallet-rest-gradle-pg) / [`quarkus-wallet-rest-gradle-mariadb`](../quarkus-wallet-rest-gradle-mariadb) — the Spring / Quarkus siblings. The domain code is identical; only the framework integration differs.
- [Ekbatan docs › Wiring with Micronaut](../../docs/wiring/micronaut.md) — the framework's Micronaut integration guide.
- [Ekbatan docs › Database › PostgreSQL](../../docs/database/postgresql.md) — column types, framework tables, jOOQ codegen.
