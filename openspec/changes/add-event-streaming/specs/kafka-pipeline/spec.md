## ADDED Requirements

### Requirement: Debezium captures events from outbox to raw topic
The integration test infrastructure SHALL configure a Debezium PostgreSQL connector that watches the `eventlog.events` table and publishes row changes to the raw topic named `ekbatan.{namespace}`.

#### Scenario: Event persisted and captured by Debezium
- **WHEN** an action is executed and events are persisted to the outbox table
- **THEN** Debezium captures the row changes via PostgreSQL logical replication and publishes them to the raw Kafka topic

#### Scenario: Raw topic receives all events from the namespace
- **WHEN** multiple actions produce events of different model types and event types
- **THEN** all events appear in the raw topic `ekbatan.{namespace}` regardless of type

### Requirement: Config-driven router publishes to output topics
The integration test infrastructure SHALL include a config-driven router that reads from the raw topic and publishes each event to all matching output topics based on a YAML configuration file. Matching is performed against `model_type` and `event_type` fields from the event.

#### Scenario: Event routed to model-type topic
- **WHEN** the router config contains a route with `model_type: Wallet` and a WalletCreatedEvent arrives on the raw topic
- **THEN** the event is published to the configured model-type output topic

#### Scenario: Event routed to event-type topic
- **WHEN** the router config contains a route with `event_type: WalletCreatedEvent` and a WalletCreatedEvent arrives on the raw topic
- **THEN** the event is published to the configured event-type output topic

#### Scenario: Event matches both model-type and event-type routes
- **WHEN** the router config contains both a `model_type: Wallet` route and an `event_type: WalletCreatedEvent` route, and a WalletCreatedEvent arrives on the raw topic
- **THEN** the event is published to both output topics

#### Scenario: Event matches no routes
- **WHEN** an event arrives on the raw topic and no route matches its model_type or event_type
- **THEN** the event is not published to any output topic (it remains only in the raw topic)

#### Scenario: Sentinel row skipped by router
- **WHEN** a sentinel row (event_type is null) arrives on the raw topic
- **THEN** the router does not publish it to any output topic

### Requirement: Output topics partitioned by model_id
Output topics (both model-type and event-type topics) SHALL use `model_id` as the Kafka partition key.

#### Scenario: Events for same model instance in same partition
- **WHEN** two events for the same wallet (same model_id) are published to an output topic
- **THEN** both events land in the same Kafka partition and are consumed in order

### Requirement: Kafka consumer dispatches to EventHandlerRegistry
The integration test infrastructure SHALL include a Kafka consumer wiring that reads from output topics, deserializes events, constructs EventEnvelope instances, and dispatches them to the EventHandlerRegistry.

#### Scenario: End-to-end event consumption
- **WHEN** an action produces a WalletCreatedEvent, and a WalletCreatedEventHandler is registered in the EventHandlerRegistry, and the Kafka consumer is wired to the event-type output topic
- **THEN** the handler receives an EventEnvelope containing the deserialized WalletCreatedEvent with correct namespace, actionId, actionName, and eventDate

#### Scenario: Handler receives only subscribed event types
- **WHEN** a handler is registered for WalletCreatedEvent and the consumer reads from the Wallet model-type topic
- **THEN** the handler receives WalletCreatedEvent envelopes but does not receive WalletMoneyDepositedEvent envelopes (those are dispatched to their own handler or silently skipped)
