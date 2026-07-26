## 1. DataSourceConfig Refactor

- [x] 1.1 Refactor DataSourceConfig from record to builder-based class per AGENTS.md conventions
- [x] 1.2 Add `resolveDialect()` — derive SQLDialect from JDBC URL using `contains()` (postgresql, mysql, mariadb)
- [x] 1.3 Add `dialect` as a public final field auto-resolved during build
- [x] 1.4 Add constructor validation (jdbcUrl, username, password required; maximumPoolSize has default)
- [x] 1.5 Update all existing usages of DataSourceConfig across codebase and tests

## 2. ConnectionProvider Refactor

- [x] 2.1 Remove `boolean primary` flag from `hikariConnectionProvider()` — URL passed as-is
- [x] 2.2 Update all existing callers of `hikariConnectionProvider(cfg, boolean)` to new signature
- [ ] 2.3 Update tests for ConnectionProvider

## 3. ShardIdentifier

- [x] 3.1 Create `ShardIdentifier` class in `io.ekbatan.core.shard` with `int group`, `int member`, `DEFAULT`, `of()`, equals/hashCode
- [ ] 3.2 Write unit tests for ShardIdentifier (equality, DEFAULT, of())

## 4. ShardAwareId + ShardingStrategy Interface

- [x] 4.1 Create `ShardAwareId` interface with `resolveShardIdentifier()` (no params)
- [x] 4.2 Create `ShardingStrategy<DB_ID>` interface with three methods: `usesShardAwareId()`, `resolveShardIdentifierById(DB_ID)`, `resolveShardIdentifier(Persistable<?>)` — no default implementations
- [x] 4.3 Create `NoShardingStrategy<DB_ID>` — `usesShardAwareId()=false`, both resolve methods return `Optional.empty()`
- [ ] 4.4 Write unit tests for NoShardingStrategy

## 5. ShardedUUID (value object)

- [x] 5.1 Create `ShardedUUID` value object wrapping UUID, implementing `ShardAwareId`
- [x] 5.2 Implement `ShardedUUID.generate(ShardIdentifier)` — UUID v7 with fixed 4-bit group + 8-bit member in rand_b
- [x] 5.3 Implement `ShardedUUID.from(UUID)` — wrap existing UUID
- [x] 5.4 Implement `resolveShardIdentifier()` — extract group + member from rand_b bits
- [x] 5.5 Add `GROUP_BITS=4` and `MEMBER_BITS=8` public constants
- [ ] 5.6 Write unit tests — round-trip (generate → resolve), uniqueness, valid UUID v7 format, all group/member combinations, value() returns underlying UUID

## 6. ShardedId<T> + Id<T> overload

- [x] 6.1 Create `ShardedId<T>` as independent final class wrapping ShardedUUID, implementing ShardAwareId and ModelId<UUID>
- [x] 6.2 Add `ShardedId.of(Class, ShardedUUID)` and `ShardedId.generate(Class, ShardIdentifier)` factory methods
- [x] 6.3 Add `Id.of(Class, ShardedUUID)` overload on existing Id<T> class
- [ ] 6.4 Write unit tests for ShardedId (creation, shard resolution, getValue, compareTo)

## 7. EmbeddedBitsShardingStrategy

- [x] 7.1 Create `EmbeddedBitsShardingStrategy` implementing `ShardingStrategy<UUID>` as singleton
- [x] 7.2 Implement `usesShardAwareId()` returning true
- [x] 7.3 Implement `resolveShardIdentifierById(UUID)` delegating to ShardedUUID.from(uuid)
- [x] 7.4 Implement `resolveShardIdentifier(Persistable<?>)` handling both Id and ShardedId types
- [ ] 7.5 Write unit tests — resolution via ID, resolution via persistable, round-trip with ShardedUUID

## 8. Sharding Configuration Classes

- [ ] 8.1 Create `ShardMemberConfig` with builder (member index, optional name, primary + secondary DataSourceConfig)
- [ ] 8.2 Create `ShardGroupConfig` with builder (group index, name, list of ShardMemberConfig)
- [ ] 8.3 Create `ShardingConfig` with builder (defaultShard, list of ShardGroupConfig)
- [ ] 8.4 Write unit tests for config validation (required fields, at least one group/member)

## 9. DatabaseRegistry

- [ ] 9.1 Create `DatabaseRegistry` with builder — holds `Map<ShardIdentifier, TransactionManager>`, cached `primary`/`secondary` DSLContext maps, `defaultShard`
- [ ] 9.2 Implement TransactionManager lookup, default TM access, validation
- [ ] 9.3 Write unit tests — single DB setup, multi-shard setup, unknown shard lookup, default shard validation

## 10. AbstractRepository Shard-Aware Methods

- [ ] 10.1 Change AbstractRepository constructor to accept `DatabaseRegistry` (+ optional `ShardingStrategy<DB_ID>`)
- [ ] 10.2 Add shard-aware `db(DB_ID)` with `usesShardAwareId()` check + strategy resolution
- [ ] 10.3 Add shard-aware `db(PERSISTABLE)` with strategy resolution
- [ ] 10.4 Add `dbs()` returning all primary DSLContexts
- [ ] 10.5 Add shard-aware `readonlyDb(DB_ID)`, `readonlyDbs()`
- [ ] 10.6 Add shard-aware `txDb(DB_ID)`, `txDb(PERSISTABLE)`
- [ ] 10.7 Add shard-aware `txDbElseDb(DB_ID)`, `txDbElseDb(PERSISTABLE)`
- [ ] 10.8 Update existing `db()`, `readonlyDb()`, `txDb()`, `txDbElseDb()` to use DatabaseRegistry for default shard
- [ ] 10.9 Update ModelRepository and EntityRepository constructors

## 11. Base CRUD Methods Shard-Aware

- [ ] 11.1 Update `add()` / `addNoResult()` / `addAll()` / `addAllNoResult()` to use `txDbElseDb(domainObject)`
- [ ] 11.2 Update `update()` / `updateNoResult()` / `updateAll()` / `updateAllNoResult()` to use `txDbElseDb(domainObject)`
- [ ] 11.3 Update `findById()` / `getById()` to use `db(id)`
- [ ] 11.4 Update `findAllByIds()` to use shard-aware routing
- [ ] 11.5 Review and update remaining query methods (findAll, findAllWhere, existsById, count, etc.)

## 12. Cross-Shard Enforcement

- [ ] 12.1 Create `CrossShardException` with `activeShard` and `requestedShard` fields
- [ ] 12.2 Add `boolean allowCrossShard` to ExecutionConfiguration (default: false)
- [ ] 12.3 Implement change grouping by shard in ActionExecutor (after action.perform(), group plan changes by shard using repository strategies)
- [ ] 12.4 Implement single-shard enforcement: throw CrossShardException if multiple shards and !allowCrossShard
- [ ] 12.5 Implement per-shard transaction execution: one inTransactionChecked() per shard via DatabaseRegistry
- [ ] 12.6 Ensure events are persisted in the same shard as their model
- [ ] 12.7 Write tests for single-shard enforcement, allowCrossShard override, per-shard transactions, event co-location

## 13. Update Existing Tests

- [ ] 13.1 Update BaseRepositoryTest to use DatabaseRegistry
- [ ] 13.2 Update all database-specific test runners (PostgreSQL, MySQL, MariaDB)
- [ ] 13.3 Update ActionExecutor tests
- [ ] 13.4 Update example wallet application (repository constructors, config)
- [ ] 13.5 Verify `./gradlew build` passes with all changes

## 14. Documentation

- [ ] 14.1 Update AGENTS.md with sharding architecture section (group=business constraint, member=performance scaling)
- [ ] 14.2 Document ShardIdentifier bit layout and the permanent contract (4-bit group + 8-bit member)
- [ ] 14.3 Document DatabaseRegistry usage for both simple and sharded setups
- [ ] 14.4 Document ShardingStrategy interface and built-in implementations
- [ ] 14.5 Document ShardedUUID, ShardedId, ShardAwareId
- [ ] 14.6 Document cross-shard behavior (single-shard default, allowCrossShard, non-atomic warning)
- [ ] 14.7 Add sharding configuration examples (YAML + Java builder)
