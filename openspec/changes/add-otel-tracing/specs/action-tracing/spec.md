## ADDED Requirements

### Requirement: Action execution span
The ActionExecutor SHALL create a span named `ekbatan.action.execute` for every action execution. The span SHALL wrap the entire execution lifecycle including retries, perform, and persistence. The span SHALL include attributes `ekbatan.action.name` (the simple class name of the action) and `ekbatan.action.principal` (the principal name). On successful completion, the span status SHALL be set to OK with attribute `ekbatan.action.outcome` set to `"success"`. On failure, the span status SHALL be set to ERROR, the exception SHALL be recorded on the span, and `ekbatan.action.outcome` SHALL be set to `"error"`.

#### Scenario: Successful action execution creates span
- **WHEN** an action is executed successfully via ActionExecutor
- **THEN** a span named `ekbatan.action.execute` is created with status OK, and attributes `ekbatan.action.name`, `ekbatan.action.principal`, and `ekbatan.action.outcome` = `"success"`

#### Scenario: Failed action execution records error on span
- **WHEN** an action execution throws an exception
- **THEN** the `ekbatan.action.execute` span has status ERROR, the exception is recorded on the span, and `ekbatan.action.outcome` = `"error"`

#### Scenario: No OTel SDK configured
- **WHEN** an action is executed and no OTel SDK is registered with GlobalOpenTelemetry
- **THEN** the action executes normally with no behavioral change (all span operations are no-ops)

### Requirement: Action perform span
The ActionExecutor SHALL create a child span named `ekbatan.action.perform` that wraps the call to `Action.perform()`. This span SHALL be a child of the `ekbatan.action.execute` span.

#### Scenario: Perform span is child of action span
- **WHEN** an action is executed
- **THEN** a span named `ekbatan.action.perform` is created as a child of `ekbatan.action.execute`

#### Scenario: Perform span captures perform duration only
- **WHEN** an action is executed
- **THEN** the `ekbatan.action.perform` span starts before `Action.perform()` is called and ends after it returns (before persistence begins)

### Requirement: Retry events on action span
When a retry occurs during action execution, the ActionExecutor SHALL add a span event named `"retry"` to the `ekbatan.action.execute` span with attributes `retry.attempt` (the attempt number, starting from 1) and `retry.exception` (the simple class name of the exception that triggered the retry). After execution completes (success or final failure), the span SHALL include attribute `ekbatan.action.retry.count` with the total number of retries performed (0 if no retries).

#### Scenario: Retry attempt recorded as span event
- **WHEN** an action execution fails with a retryable exception and is retried
- **THEN** the `ekbatan.action.execute` span contains an event named `"retry"` with attributes `retry.attempt` = 1 and `retry.exception` = the exception class name

#### Scenario: Multiple retries recorded
- **WHEN** an action execution is retried twice before succeeding
- **THEN** the `ekbatan.action.execute` span contains two `"retry"` events with `retry.attempt` = 1 and 2 respectively, and attribute `ekbatan.action.retry.count` = 2

#### Scenario: No retries
- **WHEN** an action execution succeeds on the first attempt
- **THEN** the `ekbatan.action.execute` span has attribute `ekbatan.action.retry.count` = 0 and no retry events

### Requirement: Persist changes span
The ActionExecutor SHALL create a child span named `ekbatan.action.persist` that wraps the `persistChanges` method. This span SHALL be a child of the `ekbatan.action.execute` span. If the action involves cross-shard changes, the span SHALL include attribute `ekbatan.shard.cross_shard` = true.

#### Scenario: Persist span is child of action span
- **WHEN** an action with changes is executed
- **THEN** a span named `ekbatan.action.persist` is created as a child of `ekbatan.action.execute`

#### Scenario: Cross-shard attribute set
- **WHEN** an action with cross-shard changes is executed with allowCrossShard = true
- **THEN** the `ekbatan.action.persist` span has attribute `ekbatan.shard.cross_shard` = true
