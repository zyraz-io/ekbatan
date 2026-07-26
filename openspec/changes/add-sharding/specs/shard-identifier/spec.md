## ADDED Requirements

### Requirement: ShardIdentifier is a composite numeric address
ShardIdentifier SHALL consist of two 0-based integer fields: `group` (shard group index) and `member` (member index within group). It MUST uniquely identify a shard in the two-level hierarchy.

#### Scenario: Create a ShardIdentifier
- **WHEN** `ShardIdentifier.of(0, 1)` is called
- **THEN** a ShardIdentifier with `group=0` and `member=1` is returned

#### Scenario: Default shard identifier
- **WHEN** `ShardIdentifier.DEFAULT` is accessed
- **THEN** a ShardIdentifier with `group=0` and `member=0` is returned

#### Scenario: Equality based on both fields
- **WHEN** two ShardIdentifiers have the same `group` and `member` values
- **THEN** they are equal and have the same hashCode

#### Scenario: Inequality when fields differ
- **WHEN** two ShardIdentifiers differ in `group` or `member`
- **THEN** they are not equal

### Requirement: ShardIdentifier uses 0-based indexing
ShardIdentifier MUST use 0-based indexing because indices are embedded directly into UUID bits by the EmbeddedBitsShardingStrategy.

#### Scenario: First group and member are index 0
- **WHEN** the first shard group and its first member are defined
- **THEN** they are identified as `ShardIdentifier.of(0, 0)`
