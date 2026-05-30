# Listen-to-yourself: in-process event handlers

For applications that don't need to fan events out to separate services — small monoliths, internal tools, or anywhere a Kafka cluster would be overkill — the `ekbatan-events:local-event-handler` module consumes the same `eventlog.events` outbox **inside the same JVM** via two background jobs. Same outbox row, same atomic-with-the-action guarantee, no broker.

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

Multiple handlers may subscribe to the same event type; each gets its own `event_notifications` row and its own retry/expiry lifecycle. `name()` is the cluster-stable identifier persisted on every notification row, so **treat handler names as part of your schema**: renaming a handler in code orphans the rows queued under the old name.

The registry keys subscriptions by the event class's simple name, matching `eventlog.events.event_type`. It allows multiple handlers for the same event class, but rejects two different event classes with the same simple name because those would be indistinguishable in the outbox.

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

The `delivered` column on `eventlog.events` is part of the base outbox schema (see [outbox-schema.md](../database/outbox-schema.md)) — every Ekbatan deployment already has it, written as `FALSE` on insert. The local-event-handler path adds an `events_undelivered` partial index for the fan-out scan and a new `eventlog.event_notifications` table. PostgreSQL:

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

For MySQL/MariaDB equivalents, drop the `WHERE` clauses on the partial indexes and use the dialect-appropriate UUID/JSON column types — see [Multi-database](../database/multi-database.md) and the migrations under [`ekbatan-integration-tests/local-event-handler/{mysql,mariadb}/src/test/resources/db/migration/`](../../ekbatan-integration-tests/local-event-handler/).

The denormalization is deliberate: dispatch reads everything it needs from one notification row — no JOIN to `eventlog.events`, no race if the source row is already aged out.

## Fan-out

`EventFanoutJob` polls every shard in parallel on virtual threads. Each round:

1. Drain a batch of undelivered events whose `event_type` currently has at least one registered handler (`delivered = FALSE AND event_type IN (...)`, limited, ordered by `event_date`).
2. For each event, look up subscribed handler names from `EventHandlerRegistry` and insert one `event_notifications` row per `(event × handler)` with the full denormalized context. State `PENDING`, `attempts = 0`, `next_retry_at = now`.
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
