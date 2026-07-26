## ADDED Requirements

### Requirement: Transaction span
The TransactionManager SHALL create a span named `ekbatan.transaction` for every call to `inTransactionChecked()`. The span SHALL wrap the full transaction lifecycle (begin, execute, commit/rollback). The span SHALL include attributes `ekbatan.shard.group` and `ekbatan.shard.member` identifying the shard. On commit, the span status SHALL be OK. On rollback, the span status SHALL be ERROR with the exception recorded.

#### Scenario: Successful transaction creates span
- **WHEN** a transaction is executed successfully
- **THEN** a span named `ekbatan.transaction` is created with status OK and shard attributes

#### Scenario: Failed transaction records error on span
- **WHEN** a transaction fails and is rolled back
- **THEN** the `ekbatan.transaction` span has status ERROR and the exception is recorded

#### Scenario: Transaction span is child of persist span during action execution
- **WHEN** a transaction is opened by ActionExecutor during persistChanges
- **THEN** the `ekbatan.transaction` span is a child of `ekbatan.action.persist`

#### Scenario: Transaction span is root when used outside actions
- **WHEN** TransactionManager.inTransaction() is called directly (not via ActionExecutor)
- **THEN** the `ekbatan.transaction` span is created as a root span (or child of whatever span the caller has active)

### Requirement: Shard attributes on transaction span
The TransactionManager SHALL receive the ShardIdentifier for the transaction and record it as span attributes. The attributes SHALL be `ekbatan.shard.group` (long) and `ekbatan.shard.member` (long).

#### Scenario: Shard attributes present on transaction span
- **WHEN** a transaction is executed on shard (1, 0)
- **THEN** the `ekbatan.transaction` span has attributes `ekbatan.shard.group` = 1 and `ekbatan.shard.member` = 0

### Requirement: Repository write operation spans
The AbstractRepository SHALL create spans for batch write operations invoked during action persistence: `addAllNoResult` and `updateAllNoResult`. The span name SHALL be `ekbatan.repository` with attribute `db.operation.name` set to `"INSERT"` or `"UPDATE"` respectively. Additional attributes SHALL include `ekbatan.entity.type` (the simple class name of the domain object) and `ekbatan.batch.size` (the number of records in the batch).

#### Scenario: Batch insert creates span
- **WHEN** `addAllNoResult` is called with 3 Wallet entities
- **THEN** a span named `ekbatan.repository` is created with attributes `db.operation.name` = `"INSERT"`, `ekbatan.entity.type` = `"Wallet"`, `ekbatan.batch.size` = 3

#### Scenario: Batch update creates span
- **WHEN** `updateAllNoResult` is called with 2 Wallet entities
- **THEN** a span named `ekbatan.repository` is created with attributes `db.operation.name` = `"UPDATE"`, `ekbatan.entity.type` = `"Wallet"`, `ekbatan.batch.size` = 2

#### Scenario: Repository span is child of transaction span
- **WHEN** a repository write operation occurs within a transaction during action persistence
- **THEN** the `ekbatan.repository` span is a child of the `ekbatan.transaction` span

### Requirement: Event persistence span
The EventPersister (both DualTableEventPersister and SingleTableEventPersister) SHALL create a span named `ekbatan.event.persist` when persisting action events. The span SHALL include attributes `ekbatan.action.name` (string) and `ekbatan.event.count` (long, the number of model events being persisted).

#### Scenario: Event persistence creates span
- **WHEN** an action with 2 model events is persisted
- **THEN** a span named `ekbatan.event.persist` is created with attributes `ekbatan.action.name` = the action name and `ekbatan.event.count` = 2

#### Scenario: Event persist span is child of transaction span
- **WHEN** event persistence occurs within a transaction during action persistence
- **THEN** the `ekbatan.event.persist` span is a child of the `ekbatan.transaction` span

#### Scenario: Zero events still creates span
- **WHEN** an action with no model events (Entity-only changes) is persisted
- **THEN** a span named `ekbatan.event.persist` is still created with `ekbatan.event.count` = 0
