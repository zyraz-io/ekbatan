## 1. Event Persistence Refactoring

- [ ] 1.1 Create `DenormalizedEventPersister` implementing `EventPersister` — writes one row per model event with denormalized action context (action_id, action_name, action_params, started_date, completion_date, namespace)
- [ ] 1.2 Implement sentinel row logic — when modelEvents is empty, write a single row with null model_id, model_type, event_type, and payload
- [ ] 1.3 Create `EventEntity` class for the denormalized event row (id, namespace, action_id, action_name, action_params, started_date, completion_date, model_id, model_type, event_type, payload, event_date)
- [ ] 1.4 Create `EventEntityRepository` extending AbstractRepository for the events table
- [ ] 1.5 Add OTel tracing instrumentation to `DenormalizedEventPersister.persistActionEvents()` — create `ekbatan.event.persist` span with `ekbatan.action.name` and `ekbatan.event.count` attributes
- [ ] 1.6 Remove `DualTableEventPersister` and all classes in the `dual_table` package (ActionEventEntity, ModelEventEntity, ActionEventEntityRepository, ModelEventEntityRepository)
- [ ] 1.7 Remove `SingleTableEventPersister` and all classes in the `single_table` package (ActionEventEntity, ModelEventEmbedded, ActionEventEntityRepository)

## 2. Namespace

- [ ] 2.1 Add `namespace` field to `ActionExecutor.Builder` — required, validated not null/blank
- [ ] 2.2 Pass namespace through `ActionExecutor.execute()` -> `ChangePersister.persist()` -> `EventPersister.persistActionEvents()`
- [ ] 2.3 Update `EventPersister` interface — add `namespace` parameter to `persistActionEvents()`
- [ ] 2.4 Store namespace on every event row via `DenormalizedEventPersister`

## 3. Event Handler Contract

- [ ] 3.1 Create `EventEnvelope<E>` class with fields: event, namespace, actionId, actionName, eventDate
- [ ] 3.2 Create `EventHandler<E extends ModelEvent<?>>` interface with `void handle(EventEnvelope<E> envelope)` method
- [ ] 3.3 Create `EventHandlerRegistry` — maps event class to list of handlers, supports multiple handlers per event type, provides `dispatch(EventEnvelope)` method

## 4. Database Migrations

- [ ] 4.1 Create PostgreSQL migration for `eventlog.events` table with denormalized schema (id, namespace, action_id, action_name, action_params, started_date, completion_date, model_id, model_type, event_type, payload, event_date)
- [ ] 4.2 Create MySQL migration for the events table
- [ ] 4.3 Create MariaDB migration for the events table
- [ ] 4.4 Remove dual-table migration scripts (action_events + model_events) from all database modules
- [ ] 4.5 Remove single-table migration scripts from all database modules

## 5. Test Infrastructure Updates

- [ ] 5.1 Create `BaseDenormalizedEventPersisterTest` in testFixtures — covers: persist single event, persist multiple events, persist zero events (sentinel row), verify denormalized action context, verify namespace storage
- [ ] 5.2 Create PostgreSQL test runner for denormalized event persister
- [ ] 5.3 Create MySQL test runner for denormalized event persister
- [ ] 5.4 Create MariaDB test runner for denormalized event persister
- [ ] 5.5 Remove `BaseDualTableEventPersisterTest` and all dual-table test runners (PG, MySQL, MariaDB)
- [ ] 5.6 Remove `BaseSingleTableEventPersisterTest` and all single-table test runners (PG, MySQL, MariaDB)
- [ ] 5.7 Write unit tests for `EventHandlerRegistry` — verify registration, dispatch, multiple handlers per event type
- [ ] 5.8 Write unit tests for `EventEnvelope` — verify construction and field access

## 6. Kafka Pipeline Integration Test

- [ ] 6.1 Create `postgres-event-streaming` integration test module under `ekbatan-integration-tests`
- [ ] 6.2 Set up TestContainers for Kafka, PostgreSQL, and Debezium Connect
- [ ] 6.3 Configure Debezium PostgreSQL connector to watch the events table and publish to the raw topic (`ekbatan.{namespace}`)
- [ ] 6.4 Implement config-driven router — reads from raw topic, publishes to output topics based on event-routing.yaml config, matches by model_type and event_type
- [ ] 6.5 Implement Kafka consumer wiring — connects Kafka consumer to EventHandlerRegistry, deserializes events, creates EventEnvelope, dispatches to handlers
- [ ] 6.6 Write end-to-end test: action execution -> event persisted to outbox -> Debezium captures -> raw topic -> router -> model-type output topic -> consumer handler receives event
- [ ] 6.7 Write end-to-end test: action execution -> router -> event-type output topic -> consumer handler receives specific event type
- [ ] 6.8 Write end-to-end test: action with zero events -> sentinel row persisted -> Debezium captures -> raw topic (consumer skips sentinel)
- [ ] 6.9 Write end-to-end test: single event routed to both model-type and event-type output topics

## 7. Cleanup

- [ ] 7.1 Remove `postgres-dual-table-events` integration test module
- [ ] 7.2 Remove `postgres-single-table-events` integration test module
- [ ] 7.3 Update `settings.gradle.kts` — remove dual-table and single-table subprojects, add new event-streaming subproject

## 8. Documentation

- [ ] 8.1 Update AGENTS.md — replace dual-table/single-table event persistence sections with denormalized strategy, add namespace documentation, add event handler contract section, add event streaming architecture section
