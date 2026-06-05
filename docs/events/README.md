# Events out

Ekbatan's main job is to persist events safely: the domain rows and the `eventlog.events` rows commit in the same database transaction. After that, the application chooses how to use those persisted events.

Common delivery topologies:

1. **Local only — listen to yourself.** Use [in-process event handlers](local-event-handler.md) when the consumer is part of the same app deployment: projections, notifications, audit rows, internal workflows, saga steps.
2. **Local handler as a broker publisher.** Write an event handler for an event type, and inside that handler manually publish to Kafka, Pulsar, RabbitMQ, SQS, or another broker with your own client code.
3. **CDC directly to a raw broker topic.** Use Debezium or another CDC tool to stream committed outbox rows to one topic/stream.
4. **CDC to raw topic, then fan out or rekey.** Keep one raw stream, then route it into per-model, per-event, per-tenant, or differently keyed topics using Kafka Streams, ksqlDB, Flink, Pulsar Functions, Beam, or a custom router.

Detailed references:

- **[Listen-to-yourself: in-process event handlers](local-event-handler.md)** — `@EkbatanEventHandler`, fan-out + dispatch jobs, retry & expiry, idempotency
- **[Streaming via Debezium → Kafka](event-streaming.md)** — JSON / Avro / Protobuf SMTs, the router, topic naming

All delivery paths are at-least-once. Consumers and handlers should be idempotent, usually by storing the outbox event id or another stable business key before applying side effects.

← Back to [docs index](../README.md) · [Top README](../../README.md)
