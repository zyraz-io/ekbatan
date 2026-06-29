# Listen-to-yourself: in-process event handlers

For applications that don't need to fan events out to separate services — small monoliths, internal tools, or anywhere a Kafka cluster would be overkill — the `ekbatan-events:local-event-handler` module delivers events to handlers registered in your application via two background jobs that poll the same `eventlog.events` outbox. Handlers feel like simple callbacks but inherit the full outbox guarantee: delivery survives restarts, retries on failure, and works correctly across multiple instances of the same service — no broker required.

```
Your App
   │  one transaction per action
   ▼
   _________________________________________________
  /                                                 \
  |   widgets, orders, …  +  eventlog.events tables |  ← committed atomically
  |                                                 |       (delivered = FALSE)
  \_________________________________________________/
   │
   ▼
┌─────────────────────────────────────────────────┐
│  EventFanoutJob                                 │  ← polls undelivered events,
│                                                 │    materializes one
│                                                 │    event_notifications row
│                                                 │    per (event × subscribed
│                                                 │    handler), flips delivered
└─────────────────────────────────────────────────┘
   │
   ▼
   _________________________________________________
  /                                                 \
  |   eventlog.event_notifications table            |  ← one row per
  |                                                 |       (event × handler),
  |                                                 |       state = PENDING,
  |                                                 |       next_retry_at = now
  \_________________________________________________/
   │
   ▼
┌─────────────────────────────────────────────────┐
│  EventHandlingJob                               │  ← polls due notifications,
│                                                 │    invokes the typed
│                                                 │    EventHandler, transitions
│                                                 │    to SUCCEEDED / FAILED /
│                                                 │    EXPIRED with backoff
└─────────────────────────────────────────────────┘
   │
   ▼
Your handlers (in-process, virtual threads)
```

Both jobs are `DistributedJob`s registered with the existing `JobRegistry`, so cluster exclusivity, heartbeating, and crash recovery are inherited — only one instance per cluster runs the fan-out job, only one runs the handling job.

## Defining a handler

A handler implements `EventHandler<E>`:

```java
@EkbatanEventHandler
public class WidgetCreatedEmailHandler implements EventHandler<WidgetCreatedEvent> {

    @Override public String name()                         { return "widget-created-email"; }
    @Override public Class<WidgetCreatedEvent> eventType() { return WidgetCreatedEvent.class; }

    @Override
    public void handle(EventEnvelope<WidgetCreatedEvent> envelope) throws Exception {
        var event = envelope.event;
        emailService.sendWelcome(event.modelId, event.name);
    }
}
```

Multiple handlers may subscribe to the same event type; each gets its own `event_notifications` row and its own retry/expiry lifecycle. `name()` is the canonical durable subscription id persisted on new notification rows, so **treat handler names as part of your schema**. For a safe rename, override `aliases()` with the old name: the handling job resolves queued old-name rows through aliases, while fan-out keeps writing only the canonical `name()`. Removing an alias before old rows reach a terminal state leaves those rows unresolved until expiry.

The registry keys subscriptions by the event class's simple name, matching `eventlog.events.event_type`. It allows multiple handlers for the same event class, but rejects two different event classes with the same simple name because those would be indistinguishable in the outbox. Handler names and aliases must be globally unique.

### Using a handler as a broker publisher

A local handler does not have to keep the work inside the database. You can write an event handler for an event type, and inside `handle(...)` manually publish to Kafka, Pulsar, RabbitMQ, SQS, or another broker with your own client code.

Use this shape when you want full control over the outgoing broker message: topic or stream name, message key, headers, schema, partitioning key, tenant route, destination broker, or whether one event should publish to multiple destinations. The handler receives the typed event and action context in `EventEnvelope`, so it can map an internal event into whatever public broker contract the rest of the system needs.

This is still at-least-once. If the broker publish succeeds but the process crashes before the notification row is marked `SUCCEEDED`, the handler can run again. Downstream consumers should dedupe by the outbox event id or another stable business key.

### Renaming a handler safely

Assume version 1 of your app had this handler:

```java
@EkbatanEventHandler
public class WidgetCreatedEmailHandler implements EventHandler<WidgetCreatedEvent> {

    @Override
    public String name() {
        return "widget-created-email";
    }

    @Override
    public Class<WidgetCreatedEvent> eventType() {
        return WidgetCreatedEvent.class;
    }

    @Override
    public void handle(EventEnvelope<WidgetCreatedEvent> envelope) {
        emailService.sendWelcome(envelope.event.modelId, envelope.event.name);
    }
}
```

While that version was running, fan-out created rows like this:

```text
event_id                              handler_name          state
7e4f9a2d-3c83-4fd5-a833-7b5cc2e11f00  widget-created-email  PENDING
```

Now suppose you rename the class and want the durable subscription id to be more precise:

```java
import java.util.Set;

@EkbatanEventHandler
public class WidgetCreatedWelcomeEmailHandler implements EventHandler<WidgetCreatedEvent> {

    @Override
    public String name() {
        return "widget-created-welcome-email";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("widget-created-email");
    }

    @Override
    public Class<WidgetCreatedEvent> eventType() {
        return WidgetCreatedEvent.class;
    }

    @Override
    public void handle(EventEnvelope<WidgetCreatedEvent> envelope) {
        emailService.sendWelcome(envelope.event.modelId, envelope.event.name);
    }
}
```

During the rollout, the table can contain both old-name and new-name rows:

```text
Before deploy
EventFanoutJob ── writes handler_name = 'widget-created-email' ──▶ eventlog.event_notifications

After deploy
EventFanoutJob ── writes handler_name = 'widget-created-welcome-email' ──▶ eventlog.event_notifications
```

| row | event_id | handler_name | state | how `EventHandlingJob` routes it |
|---|---|---|---|---|
| 🟨 old-name row | `7e4f9a2d-3c83-4fd5-a833-7b5cc2e11f00` | `widget-created-email` | `PENDING` | `aliases()` contains `widget-created-email` → invoke `WidgetCreatedWelcomeEmailHandler` |
| 🟨 old-name row | `313a255e-26a0-41ef-adf5-30cbefcf9b10` | `widget-created-email` | `FAILED` | `aliases()` contains `widget-created-email` → retry `WidgetCreatedWelcomeEmailHandler` |
| 🟩 new-name row | `bba0d197-8083-4760-9446-d7b781dbcb6f` | `widget-created-welcome-email` | `PENDING` | `name()` is `widget-created-welcome-email` → invoke `WidgetCreatedWelcomeEmailHandler` |

Visually, dispatch works like this:

```text
EventHandlingJob
  ├─ 🟨 row.handler_name = 'widget-created-email'
  │     └─ alias lookup: aliases() contains 'widget-created-email'
  │        └─ invokes WidgetCreatedWelcomeEmailHandler
  │
  └─ 🟩 row.handler_name = 'widget-created-welcome-email'
        └─ canonical lookup: name() == 'widget-created-welcome-email'
           └─ invokes WidgetCreatedWelcomeEmailHandler
```

So aliases are lookup-only: they let the handling job consume old rows that already exist. They do not make fan-out write old names again, and they do not replay historical events that were already fanned out under the old name.

You can remove the alias after there are no non-terminal rows under the old name. In practice, that means no `PENDING` or `FAILED` rows remain; `SUCCEEDED` and `EXPIRED` are terminal:

```sql
SELECT state, COUNT(*)
FROM eventlog.event_notifications
WHERE handler_name = 'widget-created-email'
  AND state IN ('PENDING', 'FAILED')
GROUP BY state;
```

Keeping an alias longer is harmless and often simpler, especially if backups or delayed environments might still contain old-name rows. Do not use aliases to replay history or create a second independent subscription; for that, create a new handler with its own `name()` and no alias.

The `@EkbatanEventHandler` annotation is for the Spring Boot / Quarkus / Micronaut integrations. Without DI, register handlers directly into an `EventHandlerRegistry` builder.

## Wiring (manual)

```java
var handlerRegistry = EventHandlerRegistry.eventHandlerRegistry()
        .withHandler(new WidgetCreatedEmailHandler())
        .withHandler(new WidgetCreatedIndexerHandler())
        .build();

var jobs = JobRegistry.jobRegistry()
        .connectionProvider(jobsConnectionProvider)
        .withJob(EventFanoutJob.eventFanoutJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(handlerRegistry)
                .clock(clock)
                .build())
        .withJob(EventHandlingJob.eventHandlingJob()
                .databaseRegistry(databaseRegistry)
                .eventHandlerRegistry(handlerRegistry)
                .objectMapper(objectMapper)
                .clock(clock)
                .build())
        .build();
jobs.start();
```

The default `ActionExecutor` already writes every committed event with `delivered = FALSE`, so it becomes visible to `EventFanoutJob` automatically — there's no extra persister to configure.

## Wiring via DI

With `@EkbatanEventHandler`-annotated handlers and the local-event-handler module on your classpath, the DI integration auto-creates `EventHandlerRegistry`, registers `EventFanoutJob` (always), and registers `EventHandlingJob` only when `ekbatan.local-event-handler.handling.enabled=true`.

```yaml
ekbatan:
  local-event-handler:
    fanout-poll-delay: 1s
    handling-poll-delay: 1s
    handling:
      enabled: true            # opt in to running EventHandlingJob in this process
```

The opt-in for `EventHandlingJob` exists because some deployments want the fan-out path on (so events leave the outbox into `event_notifications`) without running handlers locally — e.g. a separate worker process picks them up. Default is **off**.

The DI integrations accept both root spellings: `ekbatan.local-event-handler.*` and `ekbatan.localEventHandler.*`. Leaf properties can also be kebab-case or camelCase, for example `fanout-poll-delay` / `fanoutPollDelay`, `handling-poll-delay` / `handlingPollDelay`, `fanout-batch-size` / `fanoutBatchSize`, `handling-batch-size` / `handlingBatchSize`, `handling-max-backoff-cap` / `handlingMaxBackoffCap`, and `handling-retention-window` / `handlingRetentionWindow`.

## Schema additions

The `delivered` column on `eventlog.events` is part of the base framework table schema (see [`eventlog.events`](../database/tables/events.md)) — every Ekbatan deployment already has it, written as `FALSE` on insert. The local-event-handler path adds an `events_undelivered` partial index for the fan-out scan and a new `eventlog.event_notifications` table. PostgreSQL:

```sql
CREATE INDEX events_undelivered ON eventlog.events (event_type, event_date) WHERE delivered = FALSE;

CREATE TABLE eventlog.event_notifications (
    id              UUID         PRIMARY KEY,
    event_id        UUID         NOT NULL,
    handler_name    VARCHAR(255) NOT NULL,
    -- denormalized event + action context — copied from eventlog.events at fan-out time so
    -- dispatch reads everything it needs from a single row, no JOIN or hydration query.
    namespace       VARCHAR(255) NOT NULL,
    action_id       UUID         NOT NULL,
    action_name     VARCHAR(255) NOT NULL,
    action_params   JSONB        NOT NULL,
    started_date    TIMESTAMP    NOT NULL,
    completion_date TIMESTAMP    NOT NULL,
    model_id        VARCHAR(255),
    model_type      VARCHAR(255),
    event_type      VARCHAR(255) NOT NULL,
    payload         JSONB,
    event_date      TIMESTAMP    NOT NULL,
    -- delivery state
    state           VARCHAR(24)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_retry_at   TIMESTAMP    NOT NULL,
    created_date    TIMESTAMP    NOT NULL,
    updated_date    TIMESTAMP    NOT NULL,
    UNIQUE (event_id, handler_name)
);

CREATE INDEX event_notifications_due
    ON eventlog.event_notifications (next_retry_at)
    WHERE state IN ('PENDING', 'FAILED');
```

For MySQL/MariaDB equivalents, see the full [`eventlog.event_notifications`](../database/tables/event-notifications.md) DDL.

The denormalization is deliberate: dispatch reads everything it needs from one notification row — no JOIN to `eventlog.events`, no race if the source row is already aged out.

## Fan-out

`EventFanoutJob` polls every shard in parallel on virtual threads. Each round:

1. Drain a batch of undelivered events whose `event_type` currently has at least one registered handler (`delivered = FALSE AND event_type IN (...)`, limited, ordered by `event_date`).
2. For each event, look up subscribed canonical handler names from `EventHandlerRegistry` and insert one `event_notifications` row per `(event × handler)` with the full denormalized context. State `PENDING`, `attempts = 0`, `next_retry_at = now`.
3. Mark the source events `delivered = TRUE` so they don't get re-fanned-out next round.
4. If anything was drained, loop immediately; otherwise sleep `fanoutPollDelay`.

Idempotency: the insert uses `onConflictDoNothing()`, so a fan-out re-run after a crash that rewrites the same `(event_id, handler_name)` rows is a no-op.

Sentinel rows from `eventlog.events` (where `event_type IS NULL`) are skipped — they're metadata about actions that emitted no events and have no handler to invoke.

Events whose `event_type` has no current subscriber are also left with `delivered = FALSE`. If a matching handler is deployed later, the next fan-out round can materialize notifications for those historical rows. That makes backfill explicit through handler deployment rather than silently acknowledging events no handler could receive.

## Dispatch

`EventHandlingJob` polls every shard in parallel. Each round:

1. Drain a batch of due notifications from the primary database (`state IN ('PENDING', 'FAILED') AND next_retry_at <= now()`, limited, ordered by `next_retry_at`). Dispatch reads from primary to avoid invoking handlers from stale replica rows that were already marked complete.
2. **Pre-flight expiry check** — if `now > event_date + retentionWindow` (default 7 days), transition straight to `EXPIRED` without invoking the handler. Stale events don't burn CPU.
3. For each non-expired notification, invoke the handler in parallel on a virtual thread:
   - **Success** → `state=SUCCEEDED`, `attempts++`.
   - **Exception** + still within retention → `state=FAILED`, `attempts++`, `next_retry_at = now + capped-exponential-backoff(attempts)`.
   - **Exception** + past retention → `state=EXPIRED`, `attempts++`.
4. Bucket the outcomes and commit one batch UPDATE per bucket (succeeded / preflight-expired / postfailure-expired / retry-by-attempts).
5. If anything was drained, loop immediately; otherwise sleep `handlingPollDelay`.

The backoff is capped exponential: `30s × 2^(attempts−1)`, capped at `maxBackoffCap` (default 5 min). So the first retry waits 30s, then 1m, 2m, 4m, then 5m forever.

The default `retentionWindow` is 7 days — events that can't be handled in a week become permanent failures. Both the cap and the window are configurable on the job builders.

## Handlers must be idempotent

The pipeline guarantees **at-least-once** delivery — never less, occasionally more. Handlers can run twice for the same event in realistic scenarios:

- The handler succeeds but the dispatch JVM crashes before the notification is marked `SUCCEEDED`. The next round picks the row up again and re-invokes the handler.
- The handler partially succeeds (sends an email, then fails to write a follow-up row). The dispatch job marks the notification `FAILED`. On retry, the email goes out a second time.
- A handler invocation takes longer than the polling delay, the round times out, db-scheduler reschedules, and the row is re-claimed.

Practical patterns:

- **`INSERT ... ON CONFLICT DO NOTHING`** for any rows the handler creates, keyed by something derived from the source event id (often `event.modelId`, the notification row id, or a UUID computed from both). On replay the second insert no-ops.
- **`UPDATE ... WHERE state = '<expected source state>'`** for state transitions. The first call moves the row out of the source state; replays no-op.
- **External-effect dedup keys.** Most third-party APIs accept an `Idempotency-Key` header; pass `event.modelId` (or the notification row id) and the API enforces single-execution.
- **Chained actions.** When the handler triggers another `Action` via `ActionExecutor.execute(...)`, that action's own `eventlog.events` row carries the source event id in its action params; the consuming side dedups on that.

What you must **not** rely on:

- Ordering between handlers subscribed to the same event — they run on virtual threads concurrently.
- Ordering between events — each shard processes its own backlog independently.
- The handler being called *exactly* once. Plan for two.

## A realistic handler — calling external services

Most handlers reach for an injected service to do something useful — send a notification, write an audit row, post to an external API. The shape is the same as any handler; the only difference is the constructor-injected dependency:

```java
@EkbatanEventHandler
public class WidgetCreatedNotificationHandler implements EventHandler<WidgetCreatedEvent> {

    private final NotificationService notificationService;

    public WidgetCreatedNotificationHandler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override public String name()                         { return "widget-created-notification"; }
    @Override public Class<WidgetCreatedEvent> eventType() { return WidgetCreatedEvent.class; }

    @Override
    public void handle(EventEnvelope<WidgetCreatedEvent> envelope) throws Exception {
        notificationService.notifyWidgetCreated(envelope.event.modelId, envelope.event.name);
    }
}
```

> **Listen-to-yourself.** A handler can also inject `ActionExecutor` and call `executor.execute(...)` from inside `handle(...)` to trigger another `Action`. That's the *only* legitimate place one action chains off another action's effect — the source action has already committed by the time the handler runs, so the new action gets its own fresh transaction.

If your handler can't be made idempotent in any of those ways, you probably want the Kafka path with consumer-group offset commits, not the in-process pipeline.

## Coexistence with the Kafka path

The in-process and Debezium/Kafka paths can coexist on the same `eventlog.events` table. The outbox SMTs (`OutboxToAvroTransform`, `OutboxToProtobufTransform`) drop non-INSERT operations, so the `UPDATE delivered = TRUE` flips written by the fan-out job never become Kafka messages. Run one path or both as needed.

## See also

- [The outbox: atomic state + events](../concepts/outbox.md) — the upstream source of events
- [Distributed background jobs](../jobs/distributed-jobs.md) — `EventFanoutJob` and `EventHandlingJob` are themselves `DistributedJob`s
- [Wiring with Spring Boot](../wiring/spring.md) / [Quarkus](../wiring/quarkus.md) / [Micronaut](../wiring/micronaut.md) — `@EkbatanEventHandler` discovery
- [Streaming via Debezium → Kafka](event-streaming.md) — the alternative consumer path
