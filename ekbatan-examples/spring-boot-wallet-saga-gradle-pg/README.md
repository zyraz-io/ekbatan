# spring-boot-wallet-saga-gradle-pg

A standalone Spring Boot example that demonstrates the **saga pattern with the framework's local-event-handler**. A wallet-to-wallet transfer is decomposed into a sequence of single-aggregate actions chained via in-process event handlers. When a step fails, a compensation action runs to undo the earlier step's effect — all driven by the outbox.

No Kafka. No Debezium. No external messaging. The whole choreography happens inside one JVM via `ekbatan-events-local-event-handler` — the same in-process consumer the other examples use (just chained differently).

## What it shows

| Surface | Class |
|---|---|
| `Model` | `Wallet` (with four new saga methods: `initiateTransferOut`, `completeTransferIn`, `markTransferFailed`, `refundTransfer`) |
| `Action` | `WalletCreateAction`, `WalletDepositMoneyAction`, `WalletCloseAction`, `InitiateTransferAction`, `CompleteTransferAction`, `RefundTransferAction` |
| `EventHandler` (local) | `TransferInitiatedEventHandler`, `TransferFailedEventHandler` |
| `ModelEvent`s | `TransferInitiatedEvent`, `TransferCompletedEvent`, `TransferFailedEvent`, `TransferRefundedEvent` |
| `Repository` | `WalletRepository` |
| REST | `WalletController` (adds `POST /wallets/transfers`) |
| Integration test | `WalletSagaIntegrationTest` — exercises happy path + two compensation paths |

## The saga, in three steps

```
                       POST /wallets/transfers
                                │
                                ▼
              ┌──────────────────────────────────┐
              │ Step 1: InitiateTransferAction   │   ─── 1 transaction ───
              │  • debit source wallet (sync)    │
              │  • emit TransferInitiatedEvent   │
              └──────────────────────────────────┘
                                │
                                ▼
                  (outbox poll, EventFanoutJob)
                                │
                                ▼
              ┌──────────────────────────────────┐
              │ TransferInitiatedEventHandler    │
              │  • invokes CompleteTransferAction│
              └──────────────────────────────────┘
                                │
                                ▼
              ┌──────────────────────────────────┐
              │ Step 2: CompleteTransferAction   │   ─── 1 transaction ───
              │  if destination OPEN & matching  │
              │    currency:                     │
              │    • credit dest                 │
              │    • emit TransferCompletedEvent │
              │  else:                           │
              │    • emit TransferFailedEvent    │
              │      (on source wallet)          │
              └──────────────────────────────────┘
                                │
              ┌─────────────────┴─────────────────┐
              ▼                                   ▼
   ┌─────────────────────┐         ┌──────────────────────────────────┐
   │ TransferCompleted   │         │ TransferFailedEventHandler       │
   │ (terminal — done)   │         │  • invokes RefundTransferAction  │
   └─────────────────────┘         └──────────────────────────────────┘
                                                  │
                                                  ▼
                                  ┌──────────────────────────────────┐
                                  │ Step 3: RefundTransferAction     │   ─── 1 transaction ───
                                  │  (compensation, forward-only)    │
                                  │  • credit source back            │
                                  │  • emit TransferRefundedEvent    │
                                  └──────────────────────────────────┘
```

Three commits per transfer in the worst case (initiate, complete-but-failed, refund). Two in the happy case (initiate, complete). Each commit is a separate transaction — saga steps are independently durable.

## The headline ideas

1. **Forward-only compensation.** `RefundTransferAction` isn't a "rollback" of step 1 — it's a *new* action that credits the source back. Step 1's debit transaction was committed long ago. The compensation has its own commit and its own outbox event. The outbox makes the compensation as durable as the original.
2. **Choreography, not orchestration.** There is no central "saga state machine" class deciding what runs next. The state lives in the event log; each event handler knows only its own next step. New steps can be added by writing more events + handlers without touching existing ones.
3. **Same `EventEnvelope<X>` API as `WalletMoneyDepositedEventHandler` in `spring-boot-wallet-rest-gradle-pg`.** Each handler reads its event's data via `envelope.event.someField`. The saga's events are self-describing (every event carries `transferId`, `fromWalletId`, `toWalletId`, `amount`) so handlers don't need to deserialize the producing action's params.
4. **Failure on the source wallet's event trail.** When the destination wallet is closed or missing, `CompleteTransferAction` emits `TransferFailedEvent` on the **source** wallet (no balance change — `Wallet.markTransferFailed` attaches the event to a version-only update). The source's event history then reads `TransferInitiated → TransferFailed → TransferRefunded` — the full saga visible from a single aggregate's perspective.

## Why is the source wallet's event trail useful?

In a real audit/compliance scenario you'd query `eventlog.events WHERE model_id = ?` to reconstruct what happened to that wallet. The chain on the source tells the complete story regardless of what happened on the destination.

## Run locally

```bash
./gradlew bootRun
```

Same single-Postgres `compose.yaml` as the other Spring examples. API at `http://localhost:8080/wallets`.

```bash
# Create two wallets
curl -X POST http://localhost:8080/wallets \
    -H 'Content-Type: application/json' \
    -d '{"ownerId":"00000000-0000-0000-0000-000000000001","currency":"USD","initialBalance":"100.00"}'
# → returns wallet A's id

curl -X POST http://localhost:8080/wallets \
    -H 'Content-Type: application/json' \
    -d '{"ownerId":"00000000-0000-0000-0000-000000000002","currency":"USD","initialBalance":"0.00"}'
# → returns wallet B's id

# Happy path transfer
curl -X POST http://localhost:8080/wallets/transfers \
    -H 'Content-Type: application/json' \
    -d '{"fromWalletId":"<A.id>","toWalletId":"<B.id>","amount":"25.00"}'
# → returns 202 with the transferId; A.balance shown as 75 synchronously
# → after a moment B.balance becomes 25 (via the local-event-handler chain)

# Force a compensation
curl -X POST http://localhost:8080/wallets/<B.id>/close
curl -X POST http://localhost:8080/wallets/transfers \
    -H 'Content-Type: application/json' \
    -d '{"fromWalletId":"<A.id>","toWalletId":"<B.id>","amount":"25.00"}'
# → 202 with transferId; A.balance briefly 50, then back to 75 after compensation
```

## Test

```bash
./gradlew test
```

Three scenarios are exercised:

1. **Happy path** — transfer 25 from A (100) to B (0). After the chain settles, A = 75, B = 25.
2. **Compensation on closed destination** — close B first, transfer 25 from A. After the chain settles, A is back to 100, B is still CLOSED with balance 0.
3. **Compensation on missing destination** — transfer 25 from A to a random non-existent UUID. After the chain settles, A is back to 100.

Each scenario uses Awaitility to wait for the saga's asynchronous steps to converge (up to 15 seconds; default poll intervals are tightened via `@SpringBootTest(properties=...)` so the local-event-handler converges quickly under test).

## See also

- [`spring-boot-wallet-rest-gradle-pg`](../spring-boot-wallet-rest-gradle-pg) — the single-step listen-to-yourself example. Compare side-by-side: same event/handler API, different chaining shape.
- [`spring-boot-wallet-rest-gradle-sharded-pg`](../spring-boot-wallet-rest-gradle-sharded-pg) — the opt-in cross-shard example with `allowCrossShard(true)`. That example uses **one** action that touches both wallets and commits one independent transaction per shard; this example uses **three** actions chained by events. Different trade-offs.
- [`docs/concepts/outbox.md`](../../docs/concepts/outbox.md) — why the outbox makes saga steps durable.
- [`docs/events/local-event-handler.md`](../../docs/events/local-event-handler.md) — the in-process consumer that drives the choreography.
