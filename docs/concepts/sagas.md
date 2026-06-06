# Sagas: chaining committed actions

A saga is a way to model one business workflow as a sequence of smaller, independently committed actions. Use it when the workflow cannot honestly fit inside one database transaction: cross-shard transfers, cross-database updates, cross-service workflows, or any operation where one step may need compensation after an earlier step has already committed.

Ekbatan does **not** include a central saga orchestrator. Instead, it gives you the two pieces a saga needs:

- Each `Action` commits its own state changes and events atomically.
- The outbox lets a committed event trigger the next step reliably, either through the local-event-handler module or through CDC and a broker.

That means a saga in Ekbatan is usually: **action -> committed event -> post-commit consumer -> next step**.

## Why not one cross-shard action?

`allowCrossShard(true)` lets one action stage changes that route to multiple shards, but each shard still commits independently. If shard A commits and shard B fails, the framework cannot make those two database transactions behave like one distributed transaction.

A saga accepts that reality instead of hiding it. Each step commits locally. If a later step fails, the workflow records a failure event and runs a compensation action. The result is not one all-or-nothing transaction; it is a durable, auditable workflow with explicit recovery.

For money movement, that distinction matters:

- A cross-shard transfer action tries to debit one wallet and credit another in one logical action, but the commits are still per shard.
- A saga debits the source first, records `TransferInitiatedEvent`, tries to credit the destination in a later action, and refunds the source with a new action if the destination step cannot complete.

## Wallet transfer example

The runnable example is:

[`ekbatan-examples/spring-boot-wallet-saga-gradle-pg`](../../ekbatan-examples/spring-boot-wallet-saga-gradle-pg)

It uses one Postgres database and the in-process local-event-handler, but the shape is the same pattern you would use for a cross-shard or cross-service workflow.

The transfer is split into three actions:

| Step | Action | What commits atomically | Event |
|---|---|---|---|
| 1 | `InitiateTransferAction` | Debit the source wallet | `TransferInitiatedEvent` |
| 2 happy path | `CompleteTransferAction` | Credit the destination wallet | `TransferCompletedEvent` |
| 2 failure path | `CompleteTransferAction` | Mark the transfer failed on the source wallet | `TransferFailedEvent` |
| 3 compensation | `RefundTransferAction` | Credit the source wallet back | `TransferRefundedEvent` |

The flow looks like this:

```text
POST /wallets/transfers
        |
        v
InitiateTransferAction
  - debits source wallet
  - emits TransferInitiatedEvent
        |
        v
post-commit consumer
  - local EventHandler, Kafka/Pulsar consumer, or another worker
  - starts CompleteTransferAction or the next service step
        |
        v
CompleteTransferAction
  - if destination can receive money:
        credit destination
        emit TransferCompletedEvent
        done
  - if destination is missing, closed, or incompatible:
        emit TransferFailedEvent on the source wallet
        |
        v
post-commit consumer
  - local EventHandler, Kafka/Pulsar consumer, or another worker
  - starts RefundTransferAction or the compensation step
        |
        v
RefundTransferAction
  - credits source wallet back
  - emits TransferRefundedEvent
```

The happy path has two commits: initiate, then complete. The failure path has three commits: initiate, fail, then refund. Every commit has its own outbox rows, so the audit trail shows what happened instead of pretending the workflow was one invisible transaction.

## Forward-only compensation

The refund is not a rollback. By the time `RefundTransferAction` runs, the original debit has already committed. The compensation is a new business fact: "the source wallet was credited back because transfer X failed."

That is why the example emits `TransferRefundedEvent` instead of trying to delete or undo the earlier `TransferInitiatedEvent`. The event history should remain honest:

```text
TransferInitiated -> TransferFailed -> TransferRefunded
```

This is the main reason sagas are useful for audit-heavy domains. You can explain the system's behavior after the fact because every step was durable.

## Where the next step is started

Do **not** call another action from inside `Action.perform()`. That would hide a second transaction inside the first action's business logic and make the execution model hard to reason about.

The safe boundary is: **after the previous action has committed and its event is being consumed**. That consumer can be:

- a local-event-handler `EventHandler` in the same JVM,
- a Kafka/Pulsar/RabbitMQ/SQS consumer fed by Debezium or by a broker-publishing handler,
- a separate worker process that polls or streams the outbox,
- or a service-level coordinator that reacts to a committed event and starts the next application step.

The important part is the boundary: the next step starts from a committed event, so it gets its own transaction and its own durable outcome.

The runnable example uses local-event-handler because all saga steps live in one Spring Boot app:

```java
@EkbatanEventHandler
public class TransferInitiatedEventHandler implements EventHandler<TransferInitiatedEvent> {

    private final ActionExecutor executor;

    public TransferInitiatedEventHandler(ActionExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String name() {
        return "transfer-initiated-handler";
    }

    @Override
    public Class<TransferInitiatedEvent> eventType() {
        return TransferInitiatedEvent.class;
    }

    @Override
    public void handle(EventEnvelope<TransferInitiatedEvent> envelope) throws Exception {
        var e = envelope.event;
        executor.execute(
                () -> "transfer-saga",
                CompleteTransferAction.class,
                new CompleteTransferAction.Params(
                        e.transferId,
                        Id.of(Wallet.class, e.fromWalletId),
                        Id.of(Wallet.class, e.toWalletId),
                        e.amount));
    }
}
```

The handler runs only after `InitiateTransferAction` has committed. `CompleteTransferAction` therefore gets its own clean transaction, its own retry policy, and its own outbox rows.

With a broker topology, the shape is the same. Debezium streams `TransferInitiatedEvent` to Kafka or Pulsar, a consumer receives it after the source commit is durable, and that consumer starts the next local step in its own transaction.

## Design rules

**Put a correlation id in every saga event.** The example creates a `transferId` in `InitiateTransferAction` and carries it through `TransferInitiatedEvent`, `TransferCompletedEvent`, `TransferFailedEvent`, and `TransferRefundedEvent`.

**Make event payloads self-contained.** A handler should not need to parse the producing action's params to know what to do next. Put the next step's identifiers and amount in the event itself.

**Make each step idempotent.** Local handlers and broker consumers are at-least-once. A local handler, Kafka consumer, or worker can run twice after a crash or retry. Use a stable business key such as `(transferId, stepName)` or a unique constraint on your domain table so duplicate attempts become no-ops. The runnable example uses a `TransferStep` entity keyed by `transferId + step` before applying the destination credit or source refund.

**Record terminal business failures as events.** If the destination wallet is closed, retrying forever will not help. The example records `TransferFailedEvent`, then compensation handles the failure explicitly.

**Keep compensation forward-only.** Do not erase earlier facts. Add a new compensating action and event.

**Choose local handler vs broker based on deployment shape.** The example uses local-event-handler because all steps live in one application. If a later step belongs to another service, stream or publish the event through Debezium, Kafka, Pulsar, RabbitMQ, SQS, or whatever integration boundary your system uses, and let that service consume it.

## What this gives you

A saga does not give you instant global atomicity. Users may briefly observe intermediate state: the source wallet can be debited before the destination is credited or before the refund runs.

What it gives you is a workflow that can survive crashes and still converge:

- Each step is committed atomically with the event that announces it.
- The next step is driven from a durable outbox row.
- Failed business steps are visible in the event log.
- Compensation is explicit and auditable.

That is usually the right trade-off when `allowCrossShard(true)` would otherwise hide a partial-commit risk.

## See also

- [Listen-to-yourself: in-process event handlers](../events/local-event-handler.md) - the local handler pipeline used by the example
- [Sharding strategies](sharding.md) - why cross-shard actions are rejected by default
- [The outbox: atomic state + events](outbox.md) - why each saga step gets a durable event
- [Actions, ActionPlan, ActionExecutor](actions.md) - why actions are not nested inside `perform()`
