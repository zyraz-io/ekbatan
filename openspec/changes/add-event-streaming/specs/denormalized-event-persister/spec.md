## ADDED Requirements

### Requirement: Denormalized event row is self-contained
The DenormalizedEventPersister SHALL write one row per model event to the `eventlog.events` table. Each row MUST include the full action context (namespace, action_id, action_name, action_params, started_date, completion_date) alongside the event data (model_id, model_type, event_type, payload, event_date). No join or lookup SHALL be required to reconstruct the full event context from a single row.

#### Scenario: Single event persisted with full context
- **WHEN** an action produces one model event and the event persister is called
- **THEN** one row is written to `eventlog.events` with: a unique id, the namespace, the action_id, the action_name, the serialized action_params, started_date, completion_date, the event's model_id, model_type, event_type, serialized payload, and event_date

#### Scenario: Multiple events share action context
- **WHEN** an action produces three model events and the event persister is called
- **THEN** three rows are written to `eventlog.events`, all sharing the same action_id, action_name, action_params, started_date, and completion_date, each with its own unique id, model_id, model_type, event_type, and payload

#### Scenario: Each event row has a unique id
- **WHEN** an action produces multiple model events
- **THEN** each row in `eventlog.events` has a distinct UUID as its id

### Requirement: Sentinel row for zero-event actions
When an action produces zero model events, the DenormalizedEventPersister SHALL write a single sentinel row with the action context fields populated and event fields set to NULL. This preserves a record that the action executed.

#### Scenario: Action with no events produces sentinel row
- **WHEN** an action produces zero model events and the event persister is called
- **THEN** one row is written to `eventlog.events` with: a unique id, the namespace, the action_id, action_name, action_params, started_date, completion_date, and event_date populated, and model_id, model_type, event_type, and payload set to NULL

#### Scenario: Sentinel row is distinguishable from event rows
- **WHEN** a consumer reads rows from `eventlog.events`
- **THEN** sentinel rows can be identified by `event_type IS NULL`

### Requirement: Shard-aware persistence
The DenormalizedEventPersister SHALL route writes to the correct shard using the provided ShardIdentifier, consistent with existing EventPersister behavior.

#### Scenario: Events written to specified shard
- **WHEN** the event persister is called with a ShardIdentifier
- **THEN** all event rows are written to the database identified by that ShardIdentifier via the DatabaseRegistry

### Requirement: OTel tracing on event persistence
The DenormalizedEventPersister SHALL create a span named `ekbatan.event.persist` wrapping the persistence operation, with attributes `ekbatan.action.name` and `ekbatan.event.count`.

#### Scenario: Tracing span created for event persistence
- **WHEN** events are persisted via DenormalizedEventPersister
- **THEN** a span named `ekbatan.event.persist` is created with `ekbatan.action.name` set to the action name and `ekbatan.event.count` set to the number of model events

#### Scenario: No OTel SDK configured
- **WHEN** events are persisted and no OTel SDK is registered
- **THEN** persistence completes normally with no behavioral change

## REMOVED Requirements

### Requirement: DualTableEventPersister removed
The DualTableEventPersister and all associated classes (ActionEventEntity, ModelEventEntity, ActionEventEntityRepository, ModelEventEntityRepository in the dual_table package) SHALL be removed.

### Requirement: SingleTableEventPersister removed
The SingleTableEventPersister and all associated classes (ActionEventEntity, ModelEventEmbedded, ActionEventEntityRepository in the single_table package) SHALL be removed.
