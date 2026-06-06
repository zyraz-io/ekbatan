# spring-boot-wallet-rest-gradle-sharded-pg

A standalone Spring Boot example that demonstrates Ekbatan sharding with two PostgreSQL
databases: a global shard `(group=0, member=0)` and a Mexico shard `(group=1, member=0)`.
Wallet IDs are `ShardedId<Wallet>` values, so the shard is encoded into the UUID at creation
time and later reads/writes route from the ID automatically.

## Very important warning about `/wallets/transfers`

The `/wallets/transfers` endpoint is a **mechanics demo** for `allowCrossShard(true)`. It is
not the recommended production pattern for real money transfers.

When two wallets live on different shards, Ekbatan opens **one independent transaction per
shard**. There is no distributed transaction and no two-phase commit. That means one shard can
commit while another shard fails. In a money-transfer domain, that can look like "source wallet
debited, destination wallet not credited" unless your application has explicit compensation and
reconciliation logic.

For production transfer workflows, prefer the saga example:
[`spring-boot-wallet-saga-gradle-pg`](../spring-boot-wallet-saga-gradle-pg). That example splits
the transfer into one action per step, chains the steps through outbox/local-event-handler events,
and uses a refund action as compensation when the destination leg fails.

Keep this sharded example for understanding what `allowCrossShard(true)` does at the framework
level. Do not copy the transfer endpoint as your business transfer design.

## What it shows

| Surface | Class |
|---|---|
| Sharded `Model` | `Wallet` with `ShardedId<Wallet>` |
| Repository | `WalletRepository` with `EmbeddedBitsShardingStrategy` |
| Actions | `WalletCreateAction`, `WalletDepositMoneyAction`, `WalletCloseAction`, `WalletTransferAction` |
| REST | `WalletController` |
| Multi-shard Flyway | `EkbatanShardFlywayMigrator` |
| Integration test | `WalletControllerIntegrationTest` |

## Shard layout

The application configures two physical databases:

| Shard | Name | Purpose |
|---|---|---|
| `(0, 0)` | `global-eu-1` | Default/global shard |
| `(1, 0)` | `mexico-cdmx-1` | Wallets created with `countryCode=MX` |

`WalletCreateAction` maps country code to shard:

- `MX` -> Mexico shard `(1, 0)`
- `AU` -> Australia shard `(2, 0)`, intentionally not registered in this example
- anything else -> global shard `(0, 0)`

The unregistered Australia case demonstrates `DatabaseRegistry.effectiveShard(...)`: the wallet
ID still encodes `(2, 0)`, but because that shard is not configured yet, runtime access falls
back to the default shard.

## Run locally

You only need Docker. `./gradlew bootRun` brings up the two Postgres containers declared in
`compose.yaml` via Spring Boot's docker-compose integration and tears them down on shutdown.

```bash
./gradlew bootRun
```

The API is at `http://localhost:8080/wallets`.

```bash
# Create a global wallet
curl -X POST http://localhost:8080/wallets \
    -H 'Content-Type: application/json' \
    -d '{"countryCode":"DE","ownerId":"00000000-0000-0000-0000-000000000001","currency":"EUR","initialBalance":"100.00"}'

# Create a Mexico wallet
curl -X POST http://localhost:8080/wallets \
    -H 'Content-Type: application/json' \
    -d '{"countryCode":"MX","ownerId":"00000000-0000-0000-0000-000000000002","currency":"MXN","initialBalance":"0.00"}'

# Deposit routes to the wallet's own shard
curl -X POST http://localhost:8080/wallets/<walletId>/deposits \
    -H 'Content-Type: application/json' \
    -d '{"amount":"25.50"}'

# Read routes by decoding the shard bits from the UUID
curl http://localhost:8080/wallets/<walletId>
```

### Cross-shard mechanics demo

This call opts into `allowCrossShard(true)` and can touch two shards in one action invocation.
Again: this is a sharding mechanics demo, not the recommended production money-transfer pattern.

```bash
curl -X POST http://localhost:8080/wallets/transfers \
    -H 'Content-Type: application/json' \
    -d '{"fromWalletId":"<globalWalletId>","toWalletId":"<mexicoWalletId>","amount":"75.00"}'
```

If the two wallets are on different shards, the executor commits one transaction on the source
shard and one transaction on the destination shard. The same action id is written to each shard's
`eventlog.events` rows so downstream consumers on either shard can see the full action context.

## Test

```bash
./gradlew test
```

The integration test boots two PostgreSQL Testcontainers, runs migrations on both shards, and
exercises:

1. single-shard create/routing,
2. unregistered-shard fallback,
3. deposit routing by wallet UUID,
4. the cross-shard transfer mechanics demo,
5. close routing by wallet UUID.

## See also

- [`spring-boot-wallet-saga-gradle-pg`](../spring-boot-wallet-saga-gradle-pg) — the recommended shape for real transfer workflows: one action per step, chained by events, with compensation.
- [`docs/concepts/sharding.md`](../../docs/concepts/sharding.md) — group/member shard model and cross-shard consistency caveats.
- [`docs/database/sharding.md`](../../docs/database/sharding.md) — API/reference details for `ShardIdentifier`, `ShardedId`, and `DatabaseRegistry`.
