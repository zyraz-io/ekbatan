# ekbatan-event-streaming

Event streaming is **outside the core framework**. Ekbatan only writes action events to an outbox table (`eventlog.events`) inside the action's transaction. Getting those rows onto Kafka, Pulsar, NATS, or anything else is your responsibility — this module provides the contracts and optional plumbing to make the common cases easy.

## What Ekbatan does (and doesn't)

**Ekbatan does:** write one row per action (plus one per event emitted) into `eventlog.events`, transactionally with the action's business writes. See the outbox columns in [`V0001__create__events.sql`](../ekbatan-integration-tests/event-pipeline/common/src/main/resources/db/migration/V0001__create__events.sql).

**Ekbatan does not:** publish anywhere. No Kafka producer in the hot path, no Debezium assumption, no schema-registry integration. You choose the shipping mechanism.

## Modules in this group

- **`action-event/json`** — hand-written `ActionEvent` POJO mirroring the outbox row shape. For consumers of JSON-encoded events.
- **`action-event/avro`** — `ActionEvent.avsc` + generated `ActionEvent` class. For consumers of Avro-encoded events. Exposes the `.avsc` file via a gradle `actionEventSchema` configuration so downstream modules can mount it (e.g. into a Debezium container).
- **`debezium-smt/avro`** — optional Kafka Connect SMT (`OutboxToAvroTransform`) that encodes the outbox row and its JSON payload into Avro binary at the Connect-worker tier. Use this if you want Avro on the wire without a Schema Registry.

## Shipping options

The framework doesn't pick one. Tested setups live under [`ekbatan-integration-tests/event-pipeline/`](../ekbatan-integration-tests/event-pipeline/). Two are wired up end-to-end today:

### 1. Debezium → Kafka → consumer (JSON)

```
┌────────────┐     ┌───────────────┐     ┌──────────┐     ┌────────────┐     ┌──────────┐
│   Action   │ tx  │ eventlog.     │ CDC │ Debezium │     │   Kafka    │     │ Consumer │
│  (your     │────▶│  events       │────▶│ (pgoutput│────▶│  raw topic │────▶│  decode  │
│   code)    │     │  outbox table │     │  WAL)    │     │ (JSON)     │     │  JSON    │
└────────────┘     └───────────────┘     └──────────┘     └────────────┘     └────────────┘
                                                                │
                                                                │ optional
                                                                ▼
                                                        ┌───────────────┐
                                                        │ EventRouter   │
                                                        │ fan-out to    │
                                                        │ per-event /   │
                                                        │ per-model     │
                                                        │ topics        │
                                                        └───────────────┘
```

Consumers deserialize JSON into [`ActionEvent`](action-event/json/src/main/java/io/ekbatan/streaming/actionevent/json/ActionEvent.java).

**End-to-end setup example:** [`EventStreamingIntegrationTest`](../ekbatan-integration-tests/event-pipeline/debezium-kafka-json/src/test/java/io/ekbatan/test/event_pipeline/json/EventStreamingIntegrationTest.java) — full Testcontainers setup: Postgres + Kafka + Debezium, registers the connector, runs an action, verifies the event arrives.

### 2. Debezium → Kafka → consumer (full Avro, no Schema Registry)

```
┌────────────┐     ┌───────────────┐     ┌─────────────────────┐     ┌────────────┐     ┌──────────────┐
│   Action   │ tx  │ eventlog.     │ CDC │ Debezium Connect    │     │   Kafka    │     │  Consumer    │
│  (your     │────▶│  events       │────▶│ + OutboxToAvroXfrm  │────▶│ raw topic  │────▶│  decode Avro │
│   code)    │     │  outbox table │     │ encodes payload +   │     │ (bytes)    │     │  ActionEvent │
└────────────┘     └───────────────┘     │ whole envelope →    │     └────────────┘     │  → payload   │
                                         │ Avro bytes          │            │          └──────────────┘
                                         └─────────────────────┘            │
                                                                            │ optional
                                                                            ▼
                                                                  ┌─────────────────┐
                                                                  │ AvroEventRouter │
                                                                  │ fan-out (bytes  │
                                                                  │ pass-through)   │
                                                                  └─────────────────┘
```

The SMT encodes each record value as Avro bytes using `ActionEvent.avsc` + per-event-type payload schemas. Connector uses `ByteArrayConverter`. No Schema Registry needed; consumers carry the schemas they care about.

**End-to-end setup example:** [`EventStreamingAvroSmtIntegrationTest`](../ekbatan-integration-tests/event-pipeline/debezium-kafka-avro-smt/src/test/java/io/ekbatan/test/event_pipeline/avro_smt/EventStreamingAvroSmtIntegrationTest.java) — same Testcontainers shape, plus it mounts the SMT fat-jar and `.avsc` files into the Debezium container.

### Other shipping options (not wired up, but valid)

- **Background poller** — a job that `SELECT ... FROM eventlog.events WHERE ... FOR UPDATE SKIP LOCKED`, publishes, deletes/marks-published. Works with any broker. No CDC needed.
- **Debezium Server → Pulsar / NATS / Kinesis** — same CDC idea, different sink. The SMT and the `ActionEvent` schemas are broker-agnostic.
- **Pulsar + Pulsar IO** — analogous to Kafka Connect.

If you're building one of these, the `action-event/json` and `action-event/avro` modules give you the wire-format contracts; everything else is your glue.

## Consumers: not our business

Picking a consumer strategy (polling loop, commit semantics, retries, DLQ, parallelism, backpressure) is a whole problem of its own with well-established solutions. Use what fits your stack:

- **Spring Kafka** — `@KafkaListener`, retry topics, DLQ support out of the box
- **Vanilla `KafkaConsumer`** with your own loop — full control, more code
- **Kafka Streams** — if you need stateful stream processing
- **Pulsar consumer API** + `JSONSchema.of(ActionEvent.class)` / `AvroSchema.of(ActionEvent.class)` — first-class schema support

This repo contains `RetryingEventConsumer` and `AvroRetryingEventConsumer` under the integration tests. They exist only to give the tests something to poll against — they are **not** a recommended pattern. Don't copy them into production; reach for a real consumer framework instead.

## Where to look for examples

| You want to... | Look at |
|---|---|
| Configure Debezium + the Avro SMT | The `@BeforeAll` in [`EventStreamingAvroSmtIntegrationTest`](../ekbatan-integration-tests/event-pipeline/debezium-kafka-avro-smt/src/test/java/io/ekbatan/test/event_pipeline/avro_smt/EventStreamingAvroSmtIntegrationTest.java) |
| See the outbox row shape | [`V0001__create__events.sql`](../ekbatan-integration-tests/event-pipeline/common/src/main/resources/db/migration/V0001__create__events.sql) |
| See the wire contracts | [`ActionEvent.java`](action-event/json/src/main/java/io/ekbatan/streaming/actionevent/json/ActionEvent.java) (JSON), [`ActionEvent.avsc`](action-event/avro/src/main/avro/ActionEvent.avsc) (Avro) |
| See an example of fan-out routing | [`EventRouter`](../ekbatan-integration-tests/event-pipeline/debezium-kafka-json/src/test/java/io/ekbatan/test/event_pipeline/json/router/EventRouter.java), [`AvroEventRouter`](../ekbatan-integration-tests/event-pipeline/debezium-kafka-avro-smt/src/test/java/io/ekbatan/test/event_pipeline/avro_smt/router/AvroEventRouter.java) — same caveat as the test consumers: these are test scaffolding, not production patterns |

Write consumers how your framework of choice wants you to — Ekbatan's only contract is the `ActionEvent` schema.
