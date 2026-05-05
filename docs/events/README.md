# Events out

How committed events leave the database. Two paths — pick whichever (or both) fits the deployment.

- **[Listen-to-yourself: in-process event handlers](local-event-handler.md)** — `@EkbatanEventHandler`, fan-out + dispatch jobs, retry & expiry, idempotency
- **[Streaming via Debezium → Kafka](event-streaming.md)** — JSON / Avro / Protobuf SMTs, the router, topic naming

← Back to [docs index](../README.md) · [Top README](../../README.md)
