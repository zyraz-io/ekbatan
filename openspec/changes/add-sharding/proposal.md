## Why

Ekbatan currently operates on a single datasource — one primary and one secondary ConnectionProvider feeding one TransactionManager shared by all repositories. This limits the framework to single-database deployments. Many real-world applications need to distribute data across multiple databases for scalability, geographic locality, or tenant isolation.

Sharding support will allow Ekbatan users to distribute their domain objects across multiple databases while the framework handles routing, ID generation, and transaction management transparently.

## What Changes

Add a two-level sharding hierarchy to Ekbatan:

- **ShardGroup** — a high-level logical grouping (e.g., "europe", "americas")
- **Shard (Member)** — a physical database unit within a group (e.g., "Hamburg", "Frankfurt")

Each shard maps to its own database with its own connection pool (primary + secondary). The framework provides transparent shard routing via shard-aware IDs, a pluggable strategy interface, and a unified `DatabaseRegistry` that works for both single-database and sharded setups.

Key changes:
- New `ShardIdentifier(int group, int member)` addressing scheme with 0-based indices for UUID bit embedding
- `ShardingStrategy` interface with two built-in implementations: `NoShardingStrategy` and `EmbeddedBitsShardingStrategy`
- `DatabaseRegistry` as unified entry point replacing direct TransactionManager passing to repositories
- One TransactionManager per shard (no changes to TransactionManager itself)
- Shard-aware overloads on AbstractRepository: `db(ID)`, `db(PERSISTABLE)`, `dbs()`, `readonlyDbs()`, `txDb(ID)`, `txDbElseDb(ID)`, etc.
- `DataSourceConfig` refactored from record to builder-based class with auto-resolved dialect
- `ConnectionProvider.hikariConnectionProvider()` simplified — no more `boolean primary` flag
- Explicit primary + secondary DataSourceConfig per shard member
- Cross-shard enforcement: single-shard actions by default, configurable via `ExecutionConfiguration.allowCrossShard`

## Capabilities

### New Capabilities
- `shard-identifier`: Numeric composite shard addressing with `ShardIdentifier(group, member)`
- `sharding-strategy`: Pluggable shard routing via `ShardingStrategy<DB_ID>` interface with `ShardAwareId`, `ShardedUUID`, `ShardedId<T>`, `NoShardingStrategy`, and `EmbeddedBitsShardingStrategy`
- `database-registry`: Unified database access layer replacing direct TransactionManager usage in repositories
- `sharding-config`: Configuration classes for defining shard topology (ShardingConfig, ShardGroupConfig, ShardMemberConfig)
- `cross-shard-enforcement`: Single-shard action enforcement with configurable override
- `shard-aware-repository`: Shard-aware DSLContext resolution methods on AbstractRepository

### Modified Capabilities
- `datasource-config-refactor`: DataSourceConfig refactored from record to builder with auto-resolved dialect from JDBC URL
- `connection-provider-refactor`: ConnectionProvider simplified — boolean primary flag removed, URL passed as-is

## Impact

- **AbstractRepository** — constructor changes (DatabaseRegistry replaces TransactionManager), new shard-aware methods, base CRUD methods updated to route by shard
- **ModelRepository / EntityRepository** — constructor signature updated
- **DataSourceConfig** — breaking change: record becomes builder-based class
- **ConnectionProvider** — breaking change: `hikariConnectionProvider(cfg, boolean)` becomes `hikariConnectionProvider(cfg)`
- **ExecutionConfiguration** — new `allowCrossShard` field
- **All existing repository subclasses** — must update constructor to pass DatabaseRegistry instead of TransactionManager
- **All existing tests** — must update setup to use DatabaseRegistry
