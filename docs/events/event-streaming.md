# Streaming via Debezium → Kafka

The events table is a regular table. To turn it into a stream, point a CDC tool at it. Two high-level variants depending on what you want on the wire — pick one (or run both):

## Variant A — JSON on Kafka

The simplest setup. Debezium tails `eventlog.events`, emits each row to Kafka as JSON, and a small router (optional) fans out by `model_type` / `event_type`. No SMT, no schemas to manage.

```
Your App
   │  one transaction per action
   ▼
┌────────────────────────────────────────────────────┐
│  domain rows  +  eventlog.events    (JSONB)        │  ← committed atomically
└────────────────────────────────────────────────────┘
   │  outbox rows visible to CDC after commit
   ▼
┌────────────────────────────────────────────────────┐
│  Debezium (CDC connector)                          │  ← passthrough
│                                                    │     (no SMT)
└────────────────────────────────────────────────────┘
   │  JSON message
   ▼
┌────────────────────────────────────────────────────┐
│  Kafka — raw topic                  (JSON)         │
└────────────────────────────────────────────────────┘
   │  router fans out by model_type / event_type
   ▼
┌────────────────────────────────────────────────────┐
│  Per-model / per-event topics       (JSON)         │
└────────────────────────────────────────────────────┘
   │
   ▼
Your consumers — deserialize with Jackson
```

## Variant B — Binary on Kafka (Avro / Protobuf)

Same path through Debezium, but a **Single Message Transform** re-encodes each row into binary before it hits Kafka. The wire format is Avro or Protobuf; consumers use the matching SDK.

```
Your App
   │  one transaction per action
   ▼
┌────────────────────────────────────────────────────┐
│  domain rows  +  eventlog.events    (JSONB)        │  ← committed atomically
└────────────────────────────────────────────────────┘
   │  outbox rows visible to CDC after commit
   ▼
┌────────────────────────────────────────────────────┐
│  Debezium  +  OutboxToAvro / OutboxToProtobuf SMT  │  ← SMT re-encodes
│                                                    │     payload to binary
└────────────────────────────────────────────────────┘
   │  binary bytes
   ▼
┌────────────────────────────────────────────────────┐
│  Kafka — raw topic            (Avro / Protobuf)    │
└────────────────────────────────────────────────────┘
   │  router fans out by model_type / event_type
   ▼
┌────────────────────────────────────────────────────┐
│  Per-model / per-event topics (Avro / Protobuf)    │
└────────────────────────────────────────────────────┘
   │
   ▼
Your consumers — deserialize with Avro / Protobuf SDK
```

## Picking a variant

| Variant | Pick when… |
|---|---|
| **JSON** | Internal services; low-to-medium traffic; you want to read messages with `kafka-console-consumer` straight off the wire; no organization-wide schema governance yet. |
| **Avro** | You already have (or want) a Schema Registry; you want compile-time schemas + backward/forward-compatibility tooling; payload size matters at high volume. |
| **Protobuf** | Polyglot consumers; you already maintain `.proto` files in your org; you prefer Google's stack over Confluent's. |

Both binary variants compress notably better than JSON and provide stronger schema discipline at the cost of: managing per-event-type schemas/descriptors, deploying the SMT JAR into Kafka Connect, and consumers needing the matching SDK.

The two paths are not mutually exclusive — you can run JSON on a side topic for ops/debugging while Avro feeds production consumers. Same outbox, two Debezium connectors with different SMT configs.

---

## Why the database always stores JSON, regardless of variant

The framework writes only **JSON** to `eventlog.events.payload`. Binary encoding is performed by the SMT in Kafka Connect, never by the application. This separation is deliberate:

- **Database transactions stay small and fast** — serialization cost is kept out of the write path, and stale schemas don't make `executor.execute(...)` fail at commit time.
- **Schema mismatches surface in Kafka Connect**, not in the app's hot path. Connect can retry, log, alert, and in supported deployments route failures to a DLQ — your app stays up.
- **Schema evolution is centralized** in the SMT configuration rather than smeared across every service that produces or consumes.
- **Operators can read the outbox directly** with plain SQL — JSONB is grep-able, Avro bytes aren't.

## Consumer-side envelope contracts

Three small modules are published; pick the one matching your wire format. Each carries the same 12-field shape (id, namespace, action_id/name, action_params, timestamps, model_id/type, event_type, payload, event_date) — only the encoding differs.

| Module | Format | What's in it |
|---|---|---|
| `ekbatan-events:streaming:action-event:json` | POJO + Jackson | Reference Java class for consumers reading raw Debezium JSON. |
| `ekbatan-events:streaming:action-event:avro` | generated from `.avsc` | Avro `ActionEvent.avsc` + generated Java; exposes the schema as a Gradle named configuration so SMT/test setups can mount it. |
| `ekbatan-events:streaming:action-event:protobuf` | generated from `.proto` | `ActionEvent.proto` + generated Java + a built `.desc` (FileDescriptorSet) the SMT loads at runtime. |

## Topic naming

Convention — three levels, all prefixed with `ekbatan.{namespace}`:

```
ekbatan.{namespace}                              — raw topic (all events)
ekbatan.{namespace}.model.{ModelType}             — all events for a model type
ekbatan.{namespace}.event.{EventType}             — specific event type
```

`{namespace}` is whatever you set on `ActionExecutor.Builder.namespace(...)`. Example with `namespace = "com.example.finance"`:

```
ekbatan.com.example.finance
ekbatan.com.example.finance.model.Wallet
ekbatan.com.example.finance.event.WalletCreatedEvent
```

`{EventType}` is the event class's simple name. The default outbox persister rejects simple-name collisions within one service, so topic routing stays package-move friendly without silently merging two different event classes.

Three levels support three consumption patterns: *"give me everything from this service"* (raw), *"give me all events for this model"* (model), *"give me this specific event type"* (event).

## The router (optional but useful)

Debezium emits each outbox row to **one** topic — the raw topic. To fan out into the per-model / per-event topics above, the framework's reference pipeline puts a small **stateless router** between Kafka and Kafka:

```yaml
routes:
  - model_type: Wallet
    topic: ekbatan.com.example.finance.model.Wallet

  - event_type: WalletCreatedEvent
    topic: ekbatan.com.example.finance.event.WalletCreatedEvent

  - event_type: WalletMoneyDepositedEvent
    topic: ekbatan.com.example.finance.event.WalletMoneyDepositedEvent
```

One event can match multiple routes (it goes to all matching topics). The router is stateless, horizontally scalable, and crash-safe — the raw topic buffers events if the router is down.

A reference implementation in ~130 lines lives in [`ekbatan-integration-tests/event-pipeline/debezium-kafka-json/.../EventRouter.java`](../../ekbatan-integration-tests/event-pipeline/debezium-kafka-json/src/test/java/io/ekbatan/test/event_pipeline/json/router/EventRouter.java). Adapt it for Avro/Protobuf by swapping the deserializer and re-emitter.

The router is **not** a published framework artifact — Kafka client versions, error handling, retry, DLQ shape, and deployment topology are too org-specific to standardize as a library. Treat the integration tests as a working template.

## SMTs (Variant B only)

For binary wire encoding, the framework ships two Kafka Connect SMTs as shadow JARs:

- **`OutboxToAvroTransform`** — encodes the Debezium outbox row into an Avro `ActionEvent` envelope, with the `payload` field encoded against a **per-`event_type` Avro schema** you supply.
- **`OutboxToProtobufTransform`** — same shape, protobuf descriptor sets instead of Avro schemas.

Both:

- **Drop ops other than `c` (create) and `r` (read/snapshot).** The `UPDATE delivered = TRUE` writes from the local-event-handler fan-out path are filtered out, so the in-process and Kafka paths can coexist on the same `eventlog.events` table without double-publishing.
- **Skip sentinel rows** where `event_type IS NULL`.
- **Throw `DataException` for corrupt event rows** such as `event_type IS NOT NULL` with `payload IS NULL`, missing Avro schema / protobuf descriptor mappings, or payloads that cannot be encoded. Sentinel rows are skipped; malformed real events should be visible to operators.
- **Load schemas/descriptors from file paths** passed as transform properties at Kafka Connect startup. The schemas are exposed as Gradle named configurations on the consumer-side `action-event:avro` / `action-event:protobuf` modules so containerised setups can mount them in.

The integration tests under [`ekbatan-integration-tests/event-pipeline`](../../ekbatan-integration-tests/event-pipeline) (the `debezium-kafka-avro-smt` and `debezium-kafka-protobuf-smt` subprojects) wire up Debezium + Kafka + the SMT in TestContainers as a working reference.

### SMT error handling

In CI and staging, fail fast is useful: a missing schema mapping should break the connector so you notice the pipeline is misconfigured. In production, a single corrupt outbox row should not wedge the connector forever. Add Kafka Connect error tolerance and logging to the Debezium connector config:

```properties
# Retry transient transform/converter failures for up to 10 minutes.
errors.retry.timeout=600000
errors.retry.delay.max.ms=30000

# Skip records that still fail after retries instead of killing the connector task.
errors.tolerance=all

# Log the failed record context. Keep messages disabled unless your logs are allowed
# to contain event payloads/action params.
errors.log.enable=true
errors.log.include.messages=false
```

With this mode, a bad real event is skipped after retry/logging and the connector advances. The source row remains in `eventlog.events`, so operations can inspect it by `event_type`, `event_date`, `action_id`, or the source offset reported in the Connect logs.

Kafka Connect's built-in `errors.deadletterqueue.topic.name` support is documented for sink connector records and their transforms/converters. Do not assume it works for Debezium source SMT failures unless your Kafka Connect distribution explicitly documents source-side DLQ support. If it does, add the DLQ properties alongside the tolerance settings:

```properties
errors.deadletterqueue.topic.name=dbserver1.eventlog.events.errors
errors.deadletterqueue.context.headers.enable=true
errors.deadletterqueue.topic.replication.factor=3
```

For vanilla Apache Kafka Connect source connectors, treat Connect logs/metrics plus the durable outbox table as the recovery path, and put DLQ/retry handling in the downstream router or consumers where messages already exist in Kafka.

## Output topics partitioned by `model_id`

For per-model-type and per-event-type topics, use `model_id` as the Kafka partition key. This guarantees **per-entity ordering** — every event for wallet `abc-123` lands in the same partition and is consumed in order. Cross-entity events parallelize across partitions.

The router in the integration tests does this automatically.

## What the framework does *not* publish

- **The Kafka client wiring** (consumer/producer configs, retry, DLQ). Too infrastructure-specific.
- **Debezium connector configuration**. Properties differ across PostgreSQL / MySQL / MariaDB and across deployment topologies (snapshot mode, replica slot config, heartbeats).
- **A Confluent Schema Registry binding for Avro.** The reference SMT uses bare schema files; switch to a Schema-Registry-backed converter at the Connect level if that's how your org runs Kafka.

## See also

- [The outbox: atomic state + events](../concepts/outbox.md) — what Debezium reads from
- [Outbox schema](../database/outbox-schema.md) — the SQL DDL of `eventlog.events`
- [Listen-to-yourself: in-process event handlers](local-event-handler.md) — the alternative consumer path; can coexist with this one
- [`ekbatan-integration-tests/event-pipeline`](../../ekbatan-integration-tests/event-pipeline) — the runnable end-to-end tests for all three variants (JSON / Avro / Protobuf)
