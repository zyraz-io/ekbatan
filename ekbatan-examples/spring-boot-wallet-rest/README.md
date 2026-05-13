# spring-boot-wallet-rest

A standalone Spring Boot example that uses Ekbatan from Maven Central. The minimal JVM baseline — if you're learning the framework, start here. The native-image variant lives in a sibling project ([`spring-boot-wallet-rest-native`](../spring-boot-wallet-rest-native)) so this one stays uncluttered.

## What it shows

| Surface | Class |
|---|---|
| `Model` (event-emitting) | `Wallet` |
| `Entity` (no events) | `Notification` |
| `Action` | `WalletCreateAction`, `WalletDepositMoneyAction`, `WalletCloseAction`, `CreateNotificationAction` |
| `EventHandler` | `WalletMoneyDepositedEventHandler` |
| `Repository` | `WalletRepository`, `NotificationRepository` |
| REST | `WalletController` |
| Flyway migration | `FlywayConfiguration` — runs migrations through Ekbatan's `ShardingConfig`, single source of truth for DB credentials |
| Integration test | `WalletControllerIntegrationTest` (Testcontainers + Awaitility) |

### How the listen-to-yourself path lands

1. `POST /wallets/{id}/deposits` runs `WalletDepositMoneyAction`. It updates the `Wallet` and emits a `WalletMoneyDepositedEvent`. One transaction commits both rows.
2. The framework's fan-out + handling jobs pick up the event in-process and invoke `WalletMoneyDepositedEventHandler`.
3. The handler reads the `recipient` from `envelope.actionParams` (the serialized params of the producing action) and invokes `CreateNotificationAction` via `ActionExecutor`.
4. `CreateNotificationAction` writes a `Notification` row in a second transaction.

Two separate transactions per deposit — the outbox guarantees the handler eventually sees the deposit event, the action it invokes runs with its own retry/optimistic-locking semantics, and the `Notification` is observable through the `NotificationRepository`.

## Run locally

You only need Docker. `./gradlew bootRun` brings up the Postgres container declared in `compose.yaml` via Spring Boot's docker-compose integration and tears it down on shutdown — no manual `docker run` step.

```bash
./gradlew bootRun
```

Flyway runs the migrations on startup; the API is at `http://localhost:8080/wallets`.

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

## Test

```bash
./gradlew test
```

The integration test boots Spring with a Testcontainers PostgreSQL, runs migrations, hits each endpoint, and asserts that the `Wallet` is updated and the `Notification` row eventually appears (the handler is async, so we use Awaitility to poll up to 15 seconds).

## See also

- [`spring-boot-wallet-rest-native`](../spring-boot-wallet-rest-native) — the same app packaged for GraalVM native-image, with the small set of additions native-image requires.
- [Ekbatan docs › Wiring with Spring Boot](../../docs/wiring/spring.md) — the framework's Spring Boot integration guide.
