## ADDED Requirements

### Requirement: Namespace is required on ActionExecutor
The ActionExecutor builder SHALL require a `namespace` string. The namespace MUST NOT be null or blank. The namespace identifies the producing service or bounded context.

#### Scenario: ActionExecutor built with namespace
- **WHEN** an ActionExecutor is built with `.namespace("com.example.finance")`
- **THEN** the ActionExecutor is created successfully with the namespace stored

#### Scenario: ActionExecutor built without namespace
- **WHEN** an ActionExecutor is built without calling `.namespace()`
- **THEN** the build fails with a validation error indicating namespace is required

#### Scenario: ActionExecutor built with blank namespace
- **WHEN** an ActionExecutor is built with `.namespace("")`
- **THEN** the build fails with a validation error indicating namespace cannot be blank

### Requirement: Namespace stored on every event row
The namespace from the ActionExecutor SHALL be passed through the execution pipeline and stored on every event row persisted by the DenormalizedEventPersister, including sentinel rows.

#### Scenario: Namespace propagated to event rows
- **WHEN** an action is executed via an ActionExecutor with namespace `"com.example.finance"` and produces model events
- **THEN** every event row in `eventlog.events` has the `namespace` column set to `"com.example.finance"`

#### Scenario: Namespace on sentinel row
- **WHEN** an action with namespace `"com.example.finance"` produces zero events
- **THEN** the sentinel row has the `namespace` column set to `"com.example.finance"`

### Requirement: Namespace passed through EventPersister interface
The EventPersister interface SHALL accept a `namespace` parameter in the `persistActionEvents` method signature.

#### Scenario: EventPersister receives namespace
- **WHEN** `persistActionEvents` is called
- **THEN** the namespace parameter is available and non-null

### Requirement: Namespace drives topic naming convention
The namespace SHALL be used in the topic naming convention for event streaming. Topics follow the pattern:

- `ekbatan.{namespace}` — raw topic containing all events from the namespace
- `ekbatan.{namespace}.model.{ModelType}` — all events for a specific model type
- `ekbatan.{namespace}.event.{EventType}` — events of a specific event type

#### Scenario: Topic naming with namespace
- **WHEN** the namespace is `"com.example.finance"` and the model type is `"Wallet"`
- **THEN** the raw topic is `ekbatan.com.example.finance`, the model topic is `ekbatan.com.example.finance.model.Wallet`, and a WalletCreatedEvent topic is `ekbatan.com.example.finance.event.WalletCreatedEvent`
