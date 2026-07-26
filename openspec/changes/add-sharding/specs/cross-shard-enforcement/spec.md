## ADDED Requirements

### Requirement: Actions are single-shard by default
The framework MUST enforce that actions operate on a single shard. After `action.perform()`, the framework SHALL group plan changes by shard using each repository's strategy. If multiple shards are detected and `allowCrossShard=false`, CrossShardException SHALL be thrown.

#### Scenario: Single shard action succeeds
- **WHEN** an action's plan changes all resolve to the same shard
- **THEN** the action completes successfully with one transaction

#### Scenario: Multi-shard action throws by default
- **WHEN** an action's plan changes resolve to multiple shards and `allowCrossShard=false`
- **THEN** a `CrossShardException` is thrown before any transaction starts

### Requirement: Cross-shard can be allowed via ExecutionConfiguration
ExecutionConfiguration MUST gain a `boolean allowCrossShard` field that defaults to false. When set to true, cross-shard actions SHALL be permitted with one transaction per shard.

#### Scenario: Cross-shard allowed with per-shard transactions
- **WHEN** `ExecutionConfiguration` has `allowCrossShard=true` and changes span multiple shards
- **THEN** each shard gets its own transaction via its own TransactionManager

#### Scenario: Default is single-shard
- **WHEN** `ExecutionConfiguration` is built with defaults
- **THEN** `allowCrossShard` is false

### Requirement: Cross-shard transactions are not atomic
When `allowCrossShard=true` and changes span multiple shards, each shard's transaction is independent. The framework SHALL NOT provide distributed 2PC.

#### Scenario: Partial failure
- **WHEN** shard A's transaction commits but shard B's transaction fails
- **THEN** shard A's changes are committed and shard B's are rolled back — partial write occurs

### Requirement: Model events are persisted in the same shard as their model
Each model's `model_events` MUST be persisted in the same shard as the model's domain data, within the same transaction.

#### Scenario: Model events follow model shard
- **WHEN** a Wallet on shard (0, 1) emits a WalletCreatedEvent
- **THEN** the model_event is persisted in shard (0, 1)'s eventlog tables

### Requirement: Action events are duplicated to all involved shards in cross-shard actions
In cross-shard actions, the `action_events` record (same regular UUID) MUST be written to ALL involved shards. Each shard SHALL have a self-contained copy so that its `model_events` can reference an `action_events` record on the same shard without cross-shard foreign keys.

#### Scenario: Single-shard action events
- **WHEN** an action touches only one shard
- **THEN** the action_events record is persisted in that shard's eventlog

#### Scenario: Cross-shard action events duplicated
- **WHEN** an action touches shards (0, 0) and (1, 0) with `allowCrossShard=true`
- **THEN** the same action_events record (same UUID) is written to both shard (0, 0) and shard (1, 0)

### Requirement: Event IDs use regular UUIDs, not ShardedUUID
`action_events.id` and `model_events.id` MUST use regular UUIDs. Only domain entity IDs SHALL use ShardedUUID for shard routing.

#### Scenario: Event IDs are not shard-aware
- **WHEN** an action_events or model_events record is created
- **THEN** its ID is a regular UUID, not a ShardedUUID

### Requirement: CrossShardException carries shard details
CrossShardException MUST contain the `activeShard` (first shard accessed) and `requestedShard` (the violating shard) as ShardIdentifier fields.

#### Scenario: Exception fields
- **WHEN** a CrossShardException is thrown
- **THEN** it contains `activeShard` and `requestedShard` as ShardIdentifier instances
