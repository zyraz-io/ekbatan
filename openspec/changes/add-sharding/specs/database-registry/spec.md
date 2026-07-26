## ADDED Requirements

### Requirement: DatabaseRegistry is the unified database access layer
DatabaseRegistry MUST hold a mapping of ShardIdentifier to TransactionManager, with cached DSLContext maps for primary and secondary connections. It SHALL work for both single-database and sharded setups.

#### Scenario: Single database (non-sharded) setup
- **WHEN** a DatabaseRegistry is built with one entry at `ShardIdentifier.DEFAULT`
- **THEN** it works identically to the current single-TransactionManager setup

#### Scenario: Sharded setup
- **WHEN** a DatabaseRegistry is built with multiple entries
- **THEN** each ShardIdentifier maps to its own TransactionManager and cached DSLContexts

#### Scenario: TransactionManager lookup
- **WHEN** `registry.transactionManager(ShardIdentifier.of(0, 1))` is called
- **THEN** the TransactionManager for that shard is returned

#### Scenario: Unknown shard lookup
- **WHEN** `registry.transactionManager(unknownId)` is called for an unregistered shard
- **THEN** an exception is thrown

#### Scenario: Default shard access
- **WHEN** `registry.defaultTransactionManager()` is called
- **THEN** the TransactionManager for `registry.defaultShard` is returned

### Requirement: DSLContext maps are cached at construction
Primary and secondary DSLContext maps MUST be built from TransactionManagers during construction and stored as immutable maps.

#### Scenario: Primary DSLContext access
- **WHEN** `registry.primary.get(ShardIdentifier.of(0, 0))` is called
- **THEN** the cached DSLContext for that shard's primary connection is returned

#### Scenario: Secondary DSLContext access
- **WHEN** `registry.secondary.get(ShardIdentifier.of(0, 0))` is called
- **THEN** the cached DSLContext for that shard's secondary connection is returned

### Requirement: One TransactionManager per shard
The framework MUST use one TransactionManager instance per shard. The TransactionManager class SHALL NOT be modified.

#### Scenario: Independent transaction scoping
- **WHEN** a transaction is opened on shard (0, 0) and another on shard (0, 1)
- **THEN** each transaction uses its own TM's ScopedValue and connection pool independently

### Requirement: DatabaseRegistry has a default shard
The default shard MUST be mandatory and SHALL reference a registered shard.

#### Scenario: Default shard validation
- **WHEN** a DatabaseRegistry is built with a defaultShard that is not registered
- **THEN** construction fails with a validation error
