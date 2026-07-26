## Why

Ekbatan persists domain events atomically with domain changes via the transactional outbox pattern (EventPersister -> action_events + model_events tables). However, events remain trapped in the database — there is no mechanism to distribute them to consumers, whether within the same service ("listen to yourself") or across services ("listen to others").

The current event persistence strategies have limitations for Change Data Capture (CDC):

- `dual_table` stores events across two tables (action_events + model_events). Debezium can watch model_events for per-event granularity, but action context (action_name, action_params) lives in a separate table and is not available to consumers without a join. Debezium does not naturally combine rows from two tables.
- `single_table` embeds all model events as a JSON array inside a single action_events row. This makes CDC impractical — Debezium emits one blob per action containing all events, with no per-event routing, filtering, or partitioning possible at the CDC level.

Additionally, there is no namespace concept for identifying which service produced an event — essential for multi-service architectures where topics and event streams need clear ownership and isolation.

## What Changes

- Replace `dual_table` and `single_table` event persistence strategies with a single `denormalized` strategy: one row per event in a single table, with action context (action_name, action_params, started_date, completion_date) denormalized into every row
- Add a sentinel row mechanism for actions that produce zero events — action context preserved with null event fields
- Add `namespace` field to `ActionExecutor` and to every persisted event row, identifying the producing service or bounded context
- Introduce broker-agnostic event handler contract in `ekbatan-core`: `EventHandler<E>`, `EventEnvelope`, `EventHandlerRegistry`
- Add Kafka integration test infrastructure demonstrating the full pipeline: outbox -> Debezium -> raw topic -> config-driven router -> per-model-type and per-event-type output topics -> consumer handlers
- Remove `DualTableEventPersister`, `SingleTableEventPersister`, and all associated classes

## Capabilities

### New Capabilities
- `denormalized-event-persister`: Single-table event persistence with denormalized action context, one row per event, optimized for CDC consumption
- `namespace`: Service/bounded-context identifier on ActionExecutor, stored on every event row, drives topic naming conventions
- `event-handler`: Broker-agnostic event consumption contract (EventHandler, EventEnvelope, EventHandlerRegistry) with zero broker dependencies
- `kafka-pipeline`: Integration test demonstrating full event streaming pipeline with Debezium CDC, config-driven router, and Kafka consumer handlers

### Modified Capabilities
- `event-persister-removal`: Remove DualTableEventPersister, SingleTableEventPersister, and all associated entity classes, repositories, and migration scripts

## Impact

- **DualTableEventPersister** — removed entirely (ActionEventEntity, ModelEventEntity, ActionEventEntityRepository, ModelEventEntityRepository in dual_table package)
- **SingleTableEventPersister** — removed entirely (ActionEventEntity, ModelEventEmbedded, ActionEventEntityRepository in single_table package)
- **EventPersister interface** — signature updated to include namespace parameter
- **ActionExecutor** — new required `namespace` field on builder
- **Database schema** — new single `events` table replaces separate action_events and model_events tables
- **All existing event persistence tests** — must be rewritten for new denormalized strategy
- **Integration test modules** — `postgres-dual-table-events` and `postgres-single-table-events` removed, replaced with new integration test module
