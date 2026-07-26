## Context

Ekbatan is a Java persistence and action framework. Its event sourcing pipeline persists domain events atomically with domain changes via the EventPersister interface. Currently two implementations exist:

- `DualTableEventPersister` — writes one row to `action_events` and N rows to `model_events`. Per-event granularity exists in `model_events`, but action context (name, params) is only in `action_events`, requiring a join for full event context.
- `SingleTableEventPersister` — writes one row to `action_events` with model events embedded as a JSONB array. Compact but unsuitable for CDC — the entire action is one blob with no per-event addressability.

Neither strategy is optimized for Change Data Capture. CDC tools like Debezium read the WAL and emit one message per row change. For effective event streaming, each event needs to be a self-contained row with all context included.

The framework currently has no concept of service identity (namespace) and no handler contract for event consumption.

Key files affected:
- `EventPersister.java` — interface definition
- `ActionExecutor.java` — orchestrates action execution, calls EventPersister
- `ChangePersister.java` — coordinates domain change persistence and event extraction
- `dual_table/` package — entire package removed
- `single_table/` package — entire package removed

## Goals / Non-Goals

**Goals:**
- Single event persistence strategy optimized for both storage and CDC consumption
- Every event row is fully self-contained — no joins needed to reconstruct context
- Namespace concept for service identity, affecting event metadata and topic naming
- Broker-agnostic event handler contract that works with Kafka, Pulsar, KurrentDB, or any future broker
- Working integration test demonstrating the full Kafka pipeline as a reference implementation

**Non-Goals:**
- Framework-level Kafka/Pulsar client modules (broker wiring is application-specific, shown in integration tests)
- Config-driven router as a framework artifact (it is a standalone application, demonstrated in integration tests)
- Debezium connector configuration as framework code (infrastructure concern, documented in integration tests)
- KurrentDB integration (deferred)
- Global audit consumer / event store DB (deferred)
- Polling job consumer (deferred)
- Payload-level filtering on subscriptions (deferred — model_type + event_type covers 95%+ of use cases)

## Decisions

### 1. Single strategy: denormalized single table

Drop both `dual_table` and `single_table`. Replace with one strategy: one row per event, all context denormalized.

**Decision:** Every model event is persisted as its own row in a single `events` table. Each row includes the full action context (action_id, action_name, action_params, started_date, completion_date) alongside the event data (model_id, model_type, event_type, payload).

**Rationale:** A single self-contained row per event is the ideal unit for CDC. Debezium emits one message per row, and each message has everything a consumer needs — no joins, no lookups, no multi-table correlation. The denormalization cost (repeating action_name + action_params per event) is negligible — these are small values on append-only rows, and the write overhead is dominated by INSERT count, not row width.

**Alternative considered:** Keep dual_table and add action context columns to model_events. Rejected because it still requires two tables with no benefit — the action_events table becomes redundant once its content is denormalized into event rows.

### 2. Sentinel row for zero-event actions

**Decision:** When an action produces zero model events, a single sentinel row is written with the action context fields populated and event fields (model_id, model_type, event_type, payload) set to NULL. This preserves a record that the action executed.

**Rationale:** Without a sentinel row, actions that produce no events would vanish — no record of their execution. The sentinel row maintains the audit trail. CDC consumers can skip sentinel rows by checking `event_type IS NULL`.

### 3. Namespace on ActionExecutor

**Decision:** A `namespace` string is required on the ActionExecutor builder. It is stored on every event row and carried through to Kafka message metadata. The namespace identifies the producing service or bounded context.

**Rationale:** In multi-service architectures, events from different services must be isolated. The namespace drives topic naming (`ekbatan.{namespace}`, `ekbatan.{namespace}.model.{ModelType}`, `ekbatan.{namespace}.event.{EventType}`) and prevents collisions when multiple services have models with the same name.

**Alternative considered:** Derive namespace from package name or class metadata. Rejected because it couples infrastructure naming to code structure and is fragile under refactoring.

### 4. Topic naming convention

**Decision:** Three levels of topics, all prefixed with `ekbatan.{namespace}`:

```
ekbatan.{namespace}                              — raw topic (all events)
ekbatan.{namespace}.model.{ModelType}             — all events for a model type
ekbatan.{namespace}.event.{EventType}             — specific event type
```

Example with namespace `com.example.finance`:
```
ekbatan.com.example.finance
ekbatan.com.example.finance.model.Wallet
ekbatan.com.example.finance.event.WalletCreatedEvent
```

**Rationale:** Three levels support three consumption patterns: "give me everything from this service" (raw), "give me all events for this model" (model), "give me this specific event type" (event). Singular `model` and `event` path segments are consistent with Ekbatan's existing naming conventions (model_type, event_type, ModelEvent, EventPersister).

### 5. Config-driven router for multi-topic fan-out

**Decision:** A stateless router application reads from the raw topic and publishes each event to all matching output topics based on a YAML configuration file. One event can go to multiple topics (both a model-type topic and an event-type topic if both are configured).

```yaml
routes:
  - model_type: Wallet
    topic: ekbatan.com.example.finance.model.Wallet

  - event_type: WalletCreatedEvent
    topic: ekbatan.com.example.finance.event.WalletCreatedEvent
```

**Rationale:** Debezium (and CDC tools in general) can only route each row to one topic. The router is the minimum viable component that enables multi-topic fan-out. It is stateless (no state stores, no joins), horizontally scalable, and crash-safe (raw topic buffers events if the router is down). The added latency (10-50ms per hop) is negligible on a 200-300ms end-to-end pipeline.

**Alternative considered:** Debezium routing directly to per-model-type topics with client-side event-type filtering. Rejected because it cannot publish one event to both a model-type and event-type topic without duplicating CDC.

### 6. EventHandler contract is broker-agnostic

**Decision:** The framework provides `EventHandler<E>`, `EventEnvelope`, and `EventHandlerRegistry` in `ekbatan-core` with zero broker dependencies. Broker-specific consumer wiring (Kafka consumer, Pulsar consumer) is application code, demonstrated in integration tests.

```java
public interface EventHandler<E extends ModelEvent<?>> {
    void handle(EventEnvelope<E> envelope);
}
```

```java
public class EventEnvelope<E extends ModelEvent<?>> {
    public final E event;
    public final String namespace;
    public final UUID actionId;
    public final String actionName;
    public final Instant eventDate;
}
```

**Rationale:** Ekbatan is a persistence/action framework, not a messaging framework. The handler interface defines the contract — what a consumer looks like. The broker wiring (how events get from Kafka/Pulsar to the handler) is application-specific and varies by infrastructure. Keeping it in integration tests lets users see a complete working example and adapt it to their stack.

### 7. Kafka pipeline in integration tests, not framework code

**Decision:** The full pipeline (Debezium, router, Kafka consumer wiring) is implemented in the `ekbatan-integration-tests` module as a working reference implementation. It is not published as a framework module.

**Rationale:** The Kafka wiring is too infrastructure-specific to standardize as a library. Kafka client versions, consumer configurations, serialization formats, error handling strategies, and deployment models vary across organizations. A working integration test with TestContainers (Kafka + PostgreSQL + Debezium) is more valuable than a rigid library — users can read it, understand the pattern, and adapt it to their infrastructure.

### 8. Output topics partitioned by model_id

**Decision:** Per-model-type and per-event-type output topics use `model_id` as the Kafka partition key.

**Rationale:** This guarantees per-entity ordering — all events for wallet `abc-123` land in the same partition and are consumed in order. Cross-entity events can be processed in parallel across partitions.

## Code Examples

### Denormalized events table schema (PostgreSQL)

```sql
CREATE SCHEMA IF NOT EXISTS eventlog;

CREATE TABLE eventlog.events (
    id UUID PRIMARY KEY,
    namespace VARCHAR(255) NOT NULL,
    action_id UUID NOT NULL,
    action_name VARCHAR(255) NOT NULL,
    action_params JSONB NOT NULL,
    started_date TIMESTAMP NOT NULL,
    completion_date TIMESTAMP NOT NULL,
    model_id VARCHAR(255),
    model_type VARCHAR(255),
    event_type VARCHAR(255),
    payload JSONB,
    event_date TIMESTAMP NOT NULL
);

CREATE INDEX idx_events_action_id ON eventlog.events(action_id);
```

### ActionExecutor with namespace

```java
var executor = ActionExecutor.actionExecutor()
        .namespace("com.example.finance")
        .databaseRegistry(databaseRegistry)
        .objectMapper(objectMapper)
        .repositoryRegistry(repositoryRegistry)
        .actionRegistry(actionRegistry)
        .eventPersister(eventPersister)
        .build();
```

### EventHandler usage

```java
public class WalletCreatedEventHandler implements EventHandler<WalletCreatedEvent> {

    @Override
    public void handle(EventEnvelope<WalletCreatedEvent> envelope) {
        var event = envelope.event;
        var actionName = envelope.actionName;
        // react to wallet creation
    }
}

// Registration
var registry = new EventHandlerRegistry();
registry.register(WalletCreatedEvent.class, new WalletCreatedEventHandler());
```

### Router config (event-routing.yaml)

```yaml
routes:
  - model_type: Wallet
    topic: ekbatan.com.example.finance.model.Wallet

  - event_type: WalletCreatedEvent
    topic: ekbatan.com.example.finance.event.WalletCreatedEvent

  - event_type: WalletMoneyDepositedEvent
    topic: ekbatan.com.example.finance.event.WalletMoneyDepositedEvent
```

## Risks / Trade-offs

**[Risk] Denormalization increases row width** — Each event row includes action_name (VARCHAR) and action_params (JSONB), duplicated across all events in the same action. For actions producing many events (100+) with large action_params, this adds storage.
-> Mitigation: action_params are typically small. Even at 100 events with 1KB params, the extra storage is 100KB per action — negligible for append-only tables. The write performance impact is dominated by INSERT count, not row width.

**[Risk] Breaking change: removing dual_table and single_table** — All existing users of DualTableEventPersister and SingleTableEventPersister must migrate to the denormalized strategy. Database migrations required.
-> Mitigation: The denormalized strategy is strictly more capable. Migration is a schema change (new table) plus a configuration change (swap EventPersister implementation). Existing event data can be migrated with a one-time SQL transform.

**[Risk] Router as a critical path component** — If the router is down, events do not reach output topics.
-> Mitigation: The raw topic buffers all events while the router is down. On recovery, the router resumes from its last committed offset. No events are lost, only delayed. The router is stateless and horizontally scalable.

**[Risk] Topic proliferation** — Creating both model-type and event-type routes freely can produce many topics.
-> Mitigation: In practice, a service has 10-20 model types and teams create event-type routes only for actively consumed events. Kafka handles hundreds of topics comfortably. Convention-based naming and config-in-git provide governance.

**[Risk] Replay gap for new routes** — When a new route is added, historical events beyond the raw topic's retention window are not available in the new output topic.
-> Mitigation: Keep sufficient retention on the raw topic (30-90 days). For older events, replay from the outbox table via a one-off job.
