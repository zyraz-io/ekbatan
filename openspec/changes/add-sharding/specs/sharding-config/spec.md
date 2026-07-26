## ADDED Requirements

### Requirement: ShardingConfig describes the shard topology
ShardingConfig MUST be the top-level configuration holding groups, members, and the default shard identifier. It SHALL map to a YAML structure under `dbConfig`.

#### Scenario: YAML structure
- **WHEN** sharding is configured via YAML
- **THEN** the structure is `dbConfig.defaultShard` (group + member) and `dbConfig.groups` (list of group configs)

#### Scenario: Programmatic construction
- **WHEN** ShardingConfig is built via builder
- **THEN** it requires a defaultShard (ShardIdentifier) and at least one ShardGroupConfig

### Requirement: ShardGroupConfig describes a shard group
Each group MUST have a 0-based index, a required human-readable name, and one or more members.

#### Scenario: Group with multiple members
- **WHEN** a ShardGroupConfig is built with `group=0`, `name="europe"`, and 2 members
- **THEN** the config holds index 0, name "europe", and a list of 2 ShardMemberConfigs

#### Scenario: Group requires at least one member
- **WHEN** a ShardGroupConfig is built with no members
- **THEN** construction MUST fail with a validation error

#### Scenario: Group name is required
- **WHEN** a ShardGroupConfig is built with a blank name
- **THEN** construction MUST fail with a validation error

### Requirement: ShardMemberConfig describes a shard member
Each member MUST have a 0-based index, an optional human-readable name, and explicit primary and secondary DataSourceConfig.

#### Scenario: Member with name
- **WHEN** a ShardMemberConfig is built with `member=0`, `name="Hamburg"`, primary and secondary configs
- **THEN** the config holds all provided values

#### Scenario: Member without name
- **WHEN** a ShardMemberConfig is built without a name
- **THEN** `name` is `Optional.empty()`

#### Scenario: Member requires both primary and secondary config
- **WHEN** a ShardMemberConfig is built without a primaryConfig or secondaryConfig
- **THEN** construction MUST fail with a validation error

### Requirement: Mixed database types are supported
The framework MUST allow different shard members to use different databases (PostgreSQL, MySQL, MariaDB). Dialect SHALL be derived per-member from the JDBC URL in their DataSourceConfig.

#### Scenario: Mixed databases in one group
- **WHEN** member 0 has a PostgreSQL URL and member 1 has a MySQL URL
- **THEN** each member's DataSourceConfig resolves to its correct dialect
