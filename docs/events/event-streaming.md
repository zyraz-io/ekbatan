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

Three small modules are published; pick the one matching your wire format. Each carries the same 13-field shape (id, namespace, action_id/name, action_params, started_date, completion_date, model_id/type, event_type, payload, event_date, delivered) — only the encoding differs. See [Envelope timestamps](#envelope-timestamps) for how the three date fields are declared in each.

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

The router can be as small as a custom Kafka consumer/producer, or it can be built with Kafka Streams, ksqlDB, Apache Flink, Apache Beam, Pulsar Functions, or another stream-processing layer. Use this step when a single raw outbox stream needs to become many topics, or when a downstream consumer needs the same event stream with a different message key.

## SMTs (Variant B only)

For binary wire encoding, the framework ships two Kafka Connect SMTs as shadow JARs:

- **`OutboxToAvroTransform`** — encodes the Debezium outbox row into an Avro `ActionEvent` envelope, with the `payload` field encoded against a **per-`event_type` Avro schema** you supply.
- **`OutboxToProtobufTransform`** — same shape, protobuf descriptor sets instead of Avro schemas.

Both:

- **Drop ops other than `c` (create) and `r` (read/snapshot).** The `UPDATE delivered = TRUE` writes from the local-event-handler fan-out path are filtered out, so the in-process and Kafka paths can coexist on the same `eventlog.events` table without double-publishing.
- **Skip sentinel rows** where `event_type IS NULL`.
- **Throw `DataException` for corrupt event rows** such as `event_type IS NOT NULL` with `payload IS NULL`, missing Avro schema / protobuf descriptor mappings, or payloads that cannot be encoded. Sentinel rows are skipped; malformed real events should be visible to operators.
- **Convert each column through its Connect schema** rather than copying the raw value across. The same logical column reaches the SMT as a different Java type depending on the database, the column's precision and the connector's `time.precision.mode`: `BOOLEAN` is a real boolean on PostgreSQL but `TINYINT(1)` (an INT16) on MySQL and MariaDB, and a timestamp arrives as epoch millis, micros, nanos, an ISO-8601 string or a `java.util.Date`. All timestamps are normalised to **epoch microseconds**, which is lossless for every form Debezium produces here and leaves PostgreSQL's bytes unchanged. There is no dialect setting: the SMT branches on what the record says its columns are, so the same row yields identical bytes on all three databases. A column it cannot convert raises `DataException` naming the column, its Connect schema and the runtime type.
- **Never pass a record through untouched.** Because the SMT emits `byte[]`, its connector runs `ByteArrayConverter`, which can serialize only bytes or null - so handing back an unrecognised record guarantees the converter throws and the task dies. Every record therefore gets one of four outcomes: an outbox row is **encoded**; something recognised but deliberately not published is **skipped** silently (the `UPDATE` that flips `delivered`, a delete, and the tombstone that accompanies it); Debezium's own housekeeping - heartbeats, schema-change notices, transaction metadata - is **dropped** with a WARN logged once; and a data row from a table that is *not* the outbox raises **`DataException`**, because silently discarding someone else's data is worse than stopping. Housekeeping is told apart structurally, by the absence of the `after` field, rather than by a list of Debezium class names.
- **Prefer running this SMT before any unwrap transform.** The `c`/`r` filter reads Debezium's `op`, which lives on the envelope, so `ExtractNewRecordState` running first takes it away. The SMT still filters correctly in that case - it falls back to the row's `delivered` column - but there is one snapshot limitation to know about. See [Unwrapped records and `ExtractNewRecordState`](#unwrapped-records-and-extractnewrecordstate).
- **Tombstones are not republished.** A delete produces both a change event and a tombstone under the same key. Since the outbox key is the row id the `ActionEvent` was published under, forwarding the tombstone would let a compacted topic erase an event that had already been delivered - so pruning old `eventlog.events` rows can never unpublish the facts they recorded.
- **Validate the `ActionEvent` schema at startup.** Every field of the configured schema must have a column binding; one that does not fails the connector immediately rather than shipping that field unset on every message. A schema carrying only a subset of the fields is accepted.
- **Load schemas/descriptors from file paths** passed as transform properties at Kafka Connect startup. The schemas are exposed as Gradle named configurations on the consumer-side `action-event:avro` / `action-event:protobuf` modules so containerised setups can mount them in.

> `payload.field` and `event.type.field` name the source **column on the outbox row**, never the target field in the `ActionEvent` schema. The target field names are fixed by the schema.

The integration tests under [`ekbatan-integration-tests/event-pipeline`](../../ekbatan-integration-tests/event-pipeline) (the `debezium-kafka-avro-smt` and `debezium-kafka-protobuf-smt` subprojects) wire up Debezium + Kafka + the SMT in TestContainers as a working reference, against PostgreSQL. `debezium-kafka-dialects-smt` runs the same pipeline against MySQL and MariaDB, writing through the real event persister so the dialect-specific column bindings are the ones under test.

### Payload encoding

The `payload` column holds JSON, which the SMT re-encodes against the per-`event_type` schema you configure. Protobuf delegates this to `JsonFormat.parser()`; Avro has no equivalent, so `JsonToAvro` does it. (Avro's `DecoderFactory.jsonDecoder` is not that equivalent - it reads Avro's *own* JSON encoding, in which a union value must be tagged with its type, `{"amount":{"string":"77.10"}}`, rather than the plain form Jackson writes.)

The rule is that **the schema decides the type**, and a value that does not fit **fails, naming the field path** - it is never coerced to a default:

| Situation | Behaviour |
|---|---|
| Value's type disagrees with the schema | `DataException` naming the field, e.g. `Field 'WalletDeposit.count' is declared LONG so it needs a number, but the payload has a STRING` |
| `["null", T]` - Avro's spelling of "optional" | Decided by the schema alone: `null` to the null branch, otherwise `T` |
| A union with several non-null branches | The branch that converts *and* accounts for the most of the JSON's keys; ties break toward the tighter schema, then declaration order with a WARN |
| `decimal`, `timestamp-millis`/`-micros`, `date`, `uuid` | Handled as those types. A decimal too precise for the schema's scale fails rather than rounding silently |
| Field absent from the JSON | Its schema default, or `DataException` if it has none |
| Field present in the JSON but not in the schema | Dropped, with a WARN logged once - so an additive change to an event class cannot take the pipeline down, but the drift is visible |

### Envelope timestamps

`started_date`, `completion_date` and `event_date` are declared as **date types, not numbers**, in both binary formats:

| Format | Declaration | What a consumer's generated code hands them |
|---|---|---|
| Avro | `{"type": "long", "logicalType": "timestamp-micros"}` | `java.time.Instant` |
| Protobuf | `google.protobuf.Timestamp` | `Timestamp` (seconds + nanos) |
| JSON | ISO-8601 string, via Jackson's default | a string you parse |

This matters more than it looks. These fields used to be a bare `long` / `int64` counting microseconds, and a bare integer cannot say what it counts. A consumer reaching for the usual Java reflex — `Instant.ofEpochMilli(...)` — got a timestamp in the year 58535, with nothing anywhere raising an error; the reverse mistake lands three weeks after the epoch. The unit was recorded only in this document, so a correct consumer was one that happened to read it. Now the unit is part of the type, and the mistake cannot be expressed.

Both are microsecond resolution, because that is what the source holds: `TIMESTAMP` on PostgreSQL and `DATETIME(6)` on MySQL and MariaDB are all six fractional digits. Protobuf's `Timestamp` has a nanosecond field, so its last three digits are always zero. Avro is deliberately **not** `timestamp-nanos`: that logical type only arrived in Avro 1.12, so consumers on 1.11 would silently fall back to a bare long — reintroducing the exact ambiguity — and it would advertise precision no supported database can store.

> **Upgrading from `1.0.0-RC1` or earlier.** This is a breaking change to the published wire contract, taken deliberately before `1.0.0` froze it. Avro consumers are unaffected on the wire — a logical type annotates its underlying primitive, so the bytes are unchanged and an old schema still decodes them — but regenerating gives `Instant` where you had `long`. Protobuf consumers **must** regenerate: a message field is length-delimited where an `int64` is a varint, so old and new cannot read each other's bytes.

Timestamps *inside* `payload` are a separate matter and were never ambiguous: Jackson 3 writes `java.time` values as ISO-8601 strings by default. If your application enables `WRITE_DATES_AS_TIMESTAMPS` on the `ObjectMapper` it hands to `ActionExecutor`, its payload timestamps become bare numbers again, with all of the ambiguity described above — the framework has no opinion there, and does not override the mapper you supply.

### Unwrapped records and `ExtractNewRecordState`

Debezium normally delivers each change inside an **envelope**: `{ before, after, source, op, ts_ms }`, where the row itself sits in `after` and `op` says what happened. Debezium 3.5 defines six operations - `c` insert, `r` snapshot read, `u` update, `d` delete, `t` truncate, `m` logical-decoding message - and both SMTs read that shape and publish only `c` and `r`. The filter is an allow-list, so any operation a future Debezium release adds is withheld rather than published unexamined.

Some pipelines put Debezium's `ExtractNewRecordState` transform in front, which **unwraps** the envelope: the record value becomes the row's columns directly. The SMTs support that shape too - if the value carries the configured payload and event-type columns, it is treated as the row.

**The catch: unwrapping discards `op`.** It lives on the envelope, and the envelope is gone. The SMT then has no way to tell an `INSERT` from an `UPDATE`.

**When that matters.** Only if something updates `eventlog.events`, and exactly one thing in this framework does: the **`local-event-handler`** module sets `delivered = TRUE` on every row it fans out. So:

| Deployment | Effect of unwrapping without `op` |
|---|---|
| Kafka only, no `local-event-handler` | Harmless - nothing ever updates the outbox, so there are no `UPDATE` events to mistake for inserts |
| `local-event-handler` **and** Debezium on the same outbox | The flip would be republished as a duplicate event, so the SMT filters it on `delivered` instead - see below |

The duplicate carries the same `id` and the same payload; only `delivered` differs. Consumers that deduplicate on `id` will not notice, but anything treating each message as a distinct fact will double-process.

**What the SMT does about it.** It falls back to the row's own `delivered` column, which carries the same information for the only `UPDATE` this table ever sees. `SingleTableJsonEventPersister` inserts every row with `delivered = FALSE`, and the sole writer that sets it `TRUE` is the `local-event-handler` fanout - so `delivered = TRUE` *is* the flip, and `delivered = FALSE` *is* the insert. Duplicates are filtered without any configuration on your part.

That couples the SMT to a framework invariant defined in another module, so the invariant is pinned from both ends: `EventEntityDeliveredDefaultTest` in `ekbatan-core` guards it at the source, and the unwrapped scenario in the `debezium-kafka-dialects-smt` module performs both writes against real Debezium and asserts the second publishes nothing. If the persister ever starts inserting rows already delivered, those fail rather than letting the SMT silently publish nothing.

**One consequence worth knowing:** during an initial snapshot, rows already marked `delivered` are *not* replayed, because a snapshot read is indistinguishable from the flip without `op`. If you need the full history on a topic bootstrapped from an outbox that `local-event-handler` has already processed, use one of the options below. The SMT logs a WARN once when it takes this fallback, naming the limitation.

**Preferred configurations**, which sidestep the question entirely:

1. **Run the SMT before the unwrap transform** - `transforms=encodeAvro,unwrap` rather than `transforms=unwrap,encodeAvro`. The SMT sees the envelope and filters on `op`.
2. **Carry `op` through the unwrap.** Configure `ExtractNewRecordState` with `add.fields=op`, which adds `__op` to the row. The SMTs honour it - and an explicit `op` always wins over the inferred `delivered` signal, so snapshot replay works again. A bare `op` is also accepted if you set an empty `add.fields.prefix`.
3. **Do not run both delivery paths against one outbox.** If Kafka is the only consumer, drop `local-event-handler`; nothing then updates the table.

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
- [`eventlog.events`](../database/tables/events.md) — the SQL columns and indexes of the event table
- [Listen-to-yourself: in-process event handlers](local-event-handler.md) — the alternative consumer path; can coexist with this one
- [`ekbatan-integration-tests/event-pipeline`](../../ekbatan-integration-tests/event-pipeline) — the runnable end-to-end tests for all three variants (JSON / Avro / Protobuf)
