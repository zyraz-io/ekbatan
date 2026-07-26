## ADDED Requirements

### Requirement: EventHandler interface
An `EventHandler<E extends ModelEvent<?>>` interface SHALL be provided in `ekbatan-core`. It defines the contract for consuming a typed domain event. The interface SHALL have a single method `void handle(EventEnvelope<E> envelope)`.

#### Scenario: Implement an event handler
- **WHEN** a developer creates a class implementing `EventHandler<WalletCreatedEvent>`
- **THEN** the class must implement `void handle(EventEnvelope<WalletCreatedEvent> envelope)` which receives the typed event and its metadata

### Requirement: EventEnvelope carries event and metadata
An `EventEnvelope<E extends ModelEvent<?>>` class SHALL be provided in `ekbatan-core`. It wraps a deserialized domain event with metadata from the event row: namespace, actionId, actionName, and eventDate. The EventEnvelope SHALL have no broker-specific dependencies.

#### Scenario: EventEnvelope provides event and context
- **WHEN** a handler receives an `EventEnvelope<WalletCreatedEvent>`
- **THEN** `envelope.event` returns the typed `WalletCreatedEvent`, `envelope.namespace` returns the producing service namespace, `envelope.actionId` returns the action UUID, `envelope.actionName` returns the action class name, and `envelope.eventDate` returns the event timestamp

#### Scenario: EventEnvelope fields are non-null
- **WHEN** an EventEnvelope is constructed
- **THEN** all fields (event, namespace, actionId, actionName, eventDate) MUST be non-null

### Requirement: EventHandlerRegistry maps event types to handlers
An `EventHandlerRegistry` SHALL be provided in `ekbatan-core`. It maps event classes to their handlers and dispatches incoming events to all registered handlers for that event type.

#### Scenario: Register a handler for an event type
- **WHEN** `registry.register(WalletCreatedEvent.class, handler)` is called
- **THEN** the handler is registered for WalletCreatedEvent

#### Scenario: Multiple handlers for same event type
- **WHEN** two handlers are registered for `WalletCreatedEvent`
- **THEN** both handlers are invoked when a WalletCreatedEvent is dispatched

#### Scenario: Dispatch event to registered handlers
- **WHEN** an event of type WalletCreatedEvent is dispatched via the registry
- **THEN** all handlers registered for WalletCreatedEvent receive the EventEnvelope

#### Scenario: No handler registered for event type
- **WHEN** an event is dispatched and no handler is registered for its type
- **THEN** the event is silently skipped with no error

### Requirement: EventHandler contract is broker-agnostic
The EventHandler, EventEnvelope, and EventHandlerRegistry SHALL have zero dependencies on Kafka, Pulsar, or any other messaging broker. They reside in `ekbatan-core` alongside the domain and action abstractions.

#### Scenario: No broker dependency in event handler module
- **WHEN** the event handler classes are compiled
- **THEN** no Kafka, Pulsar, or broker client libraries are in the dependency tree
