## Context

Ekbatan is a Java persistence and action framework built on JOOQ. It currently operates on a single datasource — one primary ConnectionProvider (writes) and one secondary ConnectionProvider (read-replica) feeding a single TransactionManager. All repositories share this TransactionManager.

The framework uses `ScopedValue<Transaction>` for thread-safe transaction binding, immutable domain objects with optimistic locking, and an Action pattern that stages changes in an ActionPlan before flushing atomically.

## Goals / Non-Goals

**Goals:**
- Support distributing data across multiple databases (shards) with transparent routing
- Provide a pluggable ShardingStrategy interface with EmbeddedBitsShardingStrategy as the primary built-in implementation
- Keep zero impact on non-sharded deployments — existing code works without changes to behavior
- Unify single-database and sharded setups under one entry point (DatabaseRegistry)
- Enforce single-shard actions by default with configurable override

**Non-Goals:**
- Automatic shard rebalancing or data migration between shards
- Cross-shard transactions (distributed 2PC)
- Query routing at the SQL level (the framework routes at the repository level)
- Saga pattern for cross-shard consistency
- Admin UI or shard management tooling

## Decisions

### Event IDs use regular UUIDs, not ShardedUUID
**Decision:** Only domain entity IDs use ShardedUUID. `action_events.id` and `model_events.id` use regular UUIDs.
**Rationale:** Event infrastructure records don't need independent shard routing. `model_events` are always co-located with their model. `action_events` are either on one shard (single-shard action) or duplicated across shards (cross-shard action).

### Cross-shard actions duplicate action_events to all involved shards
**Decision:** When `allowCrossShard=true` and an action touches multiple shards, the `action_events` record (same UUID) is written to ALL involved shards. Each shard gets its own copy.
**Rationale:** Each shard must be self-contained — its `model_events` reference an `action_events` record on the same shard. No cross-shard foreign keys. Using the same UUID across shards means the action is logically identifiable everywhere.

### Two-level hierarchy — group (business) vs member (performance)
**Decision:** Sharding uses a two-level hierarchy: **group** and **member** with distinct purposes.
**Rationale:** Groups represent **business/regulatory constraints** — e.g., "Mexican data must reside on Mexican soil due to data residency regulations." Data cannot move between groups; the group boundary is a hard legal/business line. Members represent **performance/scaling** within a group — e.g., splitting Mexico's load across 3 databases. Members can be rebalanced (data moved between members within the same group) as an operational concern. This distinction is fundamental to the sharding model and is encoded into every UUID.

### ShardIdentifier uses numeric indices, not string names
**Decision:** `ShardIdentifier(int group, int member)` with 0-based indexing.
**Rationale:** Numeric indices embed directly into UUID bits without mapping. String names are for human readability in config only (ShardGroupConfig.name, ShardMemberConfig.name). The identifier is the index.

### ShardingStrategy is generic on DB_ID with three explicit methods
**Decision:** `ShardingStrategy<DB_ID>` has three methods, no defaults: `boolean usesShardAwareId()`, `Optional<ShardIdentifier> resolveShardIdentifierById(DB_ID id)`, `Optional<ShardIdentifier> resolveShardIdentifier(Persistable<?> persistable)`. Every implementation must explicitly implement all three.
**Rationale:** `usesShardAwareId()` distinguishes "no sharding" from "sharding active but old record can't be resolved" — both return `Optional.empty()` from resolve methods, but mean different things. The boolean lets the repository skip resolution entirely when sharding is not active. `resolveShardIdentifier(Persistable<?>)` enables future field-based strategies that route by domain fields rather than ID. Generic `<DB_ID>` gives type-safe ID resolution without coupling to UUID.

### ShardAwareId interface for value objects that carry shard info
**Decision:** `ShardAwareId` is an interface with a single method `ShardIdentifier resolveShardIdentifier()` (no parameters). Value objects that carry shard info implement it. The framework provides `ShardedUUID` as one such implementation.
**Rationale:** A value object that wraps an ID knows its own shard — it doesn't need to be told. The interface is decoupled from any specific ID type. Clients can create their own implementations (`ShardedString`, `ShardedLong`, etc.).

### ShardedId<T> exists independently alongside Id<T>
**Decision:** `ShardedId<T>` is an independent final class parallel to `Id<T>`. It wraps a `ShardedUUID` and implements `ShardAwareId`. It does NOT extend `Id<T>`. `Id<T>` stays final and unchanged (except gaining an `of(Class, ShardedUUID)` overload). Sharded models declare `ShardedId<Wallet>` as their ID type, non-sharded models keep `Id<Wallet>`.
**Rationale:** Complete independence means `Id<T>` is untouched — no `final` removal, no sealed hierarchy, no domain layer pollution. The type distinction is explicit: `Model<Wallet, ShardedId<Wallet>, WalletState>` immediately tells the reader this entity is sharded. `ShardedId<T>` implements `ShardAwareId`, so `wallet.id.resolveShardIdentifier()` works directly in domain/action code.

### ShardedUUID is a value object, not a utility class
**Decision:** `ShardedUUID` wraps a UUID, implements `ShardAwareId`. It is NOT a singleton or utility class — many instances exist, each wrapping a different UUID. Has `generate(ShardIdentifier)` and `from(UUID)` factory methods, `value()` to get the underlying UUID, and `resolveShardIdentifier()` to extract shard info from its bits.
**Rationale:** A UUID with shard bits is a value — like UUID itself. `generate()` creates new ones, `from()` wraps existing ones loaded from DB. The shard info is always recoverable from the UUID bits via `resolveShardIdentifier()`.

### ShardedUUID uses fixed 4-bit group + 8-bit member
**Decision:** `ShardedUUID` (the utility used by `EmbeddedBitsShardingStrategy`) uses a fixed bit layout: 4 bits for group (max 16) + 8 bits for member (max 256 per group) = 12 bits total, leaving 62 random bits in UUID v7. This is a property of ShardedUUID specifically, not a universal constraint on the framework.
**Rationale:** Groups represent business/regulatory zones (few — typically 2-10), members represent performance shards (can grow — up to 256 per group). 4+8 matches this asymmetry. The bit layout is a permanent contract for all UUIDs generated by this implementation — changing it later would make existing IDs unreadable. Making it configurable adds a knob that can be set wrong with catastrophic consequences. If someone needs a different layout (e.g., 8+8), they implement a custom `ShardingStrategy` with their own UUID encoding.

### UUID v7 with shard bits in rand_b (ShardedUUID)
**Decision:** `ShardedUUID` is a utility class that generates UUID v7 with (group, member) encoded in the first 12 bits of rand_b. Hand-rolled, no third-party library.
**Rationale:** UUID v7 is time-ordered (good for database indexes), widely recognized, and has 74 random bits available. 12 bits for shard leaves 62 random bits — collision probability at 10K IDs/ms is ~10⁻¹². No Java library supports embedding custom data in UUID v7 random bits. Hand-rolling is ~10 lines of bit manipulation. UUID v7 chosen over v8 because v7 is universally recognized as time-ordered, while v8 means "custom" and raises questions.

### ShardingStrategy lives on Repository, not Persistable
**Decision:** The strategy is passed to the repository constructor.
**Rationale:** Keeps the domain layer (`io.ekbatan.core.domain`) completely clean of infrastructure concerns. The repository already maps 1:1 to an entity type, so per-repo strategy = per-type strategy. Solves the "read without instance" problem — the repo has the strategy for `findById(id)` without needing a Persistable instance.

### One TransactionManager per shard, no subclassing
**Decision:** Each shard gets its own TransactionManager instance. TransactionManager class is not modified.
**Rationale:** TransactionManager already does exactly what a single shard needs. Each shard is a separate database. ScopedValue<Transaction> per TM gives clean transaction scoping. Avoids complicating TM with multi-shard routing.

### DatabaseRegistry replaces direct TransactionManager passing
**Decision:** Repositories take a DatabaseRegistry instead of a TransactionManager.
**Rationale:** Unifies single-DB and sharded setups. A non-sharded deployment is just a registry with one entry. Holds the TM map plus cached DSLContext maps (primary + secondary). One object to pass instead of TM + ShardMap + default shard identifier.

### Explicit primary + secondary DataSourceConfig per shard member
**Decision:** Each shard member has separate primary and secondary DataSourceConfig objects.
**Rationale:** `targetServerType` is PostgreSQL-specific — the framework should not append database-specific URL parameters. MySQL uses `replication://`, MariaDB has its own mechanism. The user provides complete URLs. Framework passes them through as-is.

### Dialect derived from JDBC URL
**Decision:** `DataSourceConfig.resolveDialect()` derives dialect from URL using `contains()`. No explicit dialect field.
**Rationale:** Single source of truth — no mismatch between URL and dialect. `contains()` handles wrapper drivers (e.g., `jdbc:aws-wrapper:postgresql://...`). Covers standard, cloud, and proxy scenarios.

### DataSourceConfig refactored to builder
**Decision:** DataSourceConfig becomes a builder-based class (from record).
**Rationale:** 8 fields with 5 optional — record constructor requires many `Optional.empty()` calls. Builder is cleaner and consistent with every other class in the project. Dialect is auto-resolved during build.

### Framework provides flexibility with sensible defaults
**Decision:** Framework ships `NoShardingStrategy` and `EmbeddedBitsShardingStrategy` (using `ShardedUUID`). The `ShardingStrategy` interface is open — clients can implement their own strategies with their own shard-aware ID types. The framework does not restrict users to ShardedUUID.
**Rationale:** Ekbatan is a library — locking users into one approach gets the library replaced. A client might need `ShardingStrategy<Long>` with hash-based routing, or `ShardingStrategy<String>` with prefix-based routing. The interface accommodates all of these. The framework provides the UUID v7 embedded-bits approach as the primary ready-to-use implementation.

### Cross-shard actions: one transaction per shard, not atomic
**Decision:** After `action.perform()`, the framework groups plan changes by shard (using each repository's strategy). If multiple shards are detected and `allowCrossShard=false`, `CrossShardException` is thrown. If `allowCrossShard=true`, each shard gets its own transaction via its own TransactionManager. Events are persisted in the same shard as their model.
**Rationale:** Each shard has its own TransactionManager with its own connection pool. Distributed 2PC is a non-goal. Cross-shard actions are not atomic — shard A's transaction might commit while shard B's rolls back. Users accept this risk by setting `allowCrossShard=true`. Each shard's eventlog tables contain only events for models on that shard.

### DSLContext maps cached at construction in DatabaseRegistry
**Decision:** Primary and secondary DSLContext maps are built from TransactionManagers once during DatabaseRegistry construction.
**Rationale:** DSL.using() is lightweight but no reason to create per-call. Maps are immutable — built once, shared across all repositories.

## Code Examples

### ShardIdentifier

```java
package io.ekbatan.core.shard;

public final class ShardIdentifier {

    public static final ShardIdentifier DEFAULT = new ShardIdentifier(0, 0);

    public final int group;
    public final int member;

    private ShardIdentifier(int group, int member) {
        this.group = group;
        this.member = member;
    }

    public static ShardIdentifier of(int group, int member) {
        return new ShardIdentifier(group, member);
    }

    // equals, hashCode (both fields), toString
}
```

### ShardAwareId Interface

```java
package io.ekbatan.core.shard;

public interface ShardAwareId {
    ShardIdentifier resolveShardIdentifier();
}
```

### ShardingStrategy Interface

```java
package io.ekbatan.core.shard;

public interface ShardingStrategy<DB_ID> {
    boolean usesShardAwareId();
    Optional<ShardIdentifier> resolveShardIdentifierById(DB_ID id);
    Optional<ShardIdentifier> resolveShardIdentifier(Persistable<?> persistable);
}
```

### NoShardingStrategy

```java
package io.ekbatan.core.shard;

public final class NoShardingStrategy<DB_ID> implements ShardingStrategy<DB_ID> {

    @SuppressWarnings("unchecked")
    public static <T> NoShardingStrategy<T> instance() {
        return (NoShardingStrategy<T>) INSTANCE;
    }

    private static final NoShardingStrategy<?> INSTANCE = new NoShardingStrategy<>();

    private NoShardingStrategy() {}

    @Override
    public boolean usesShardAwareId() {
        return false;
    }

    @Override
    public Optional<ShardIdentifier> resolveShardIdentifierById(DB_ID id) {
        return Optional.empty();
    }

    @Override
    public Optional<ShardIdentifier> resolveShardIdentifier(Persistable<?> persistable) {
        return Optional.empty();
    }
}
```

### EmbeddedBitsShardingStrategy

```java
package io.ekbatan.core.shard;

public final class EmbeddedBitsShardingStrategy implements ShardingStrategy<UUID> {

    public static final EmbeddedBitsShardingStrategy INSTANCE = new EmbeddedBitsShardingStrategy();

    private EmbeddedBitsShardingStrategy() {}

    @Override
    public boolean usesShardAwareId() {
        return true;
    }

    @Override
    public Optional<ShardIdentifier> resolveShardIdentifierById(UUID id) {
        if (id.version() != 7) {
            return Optional.empty();  // old pre-sharding UUID v4 → default shard
        }
        return Optional.of(ShardedUUID.from(id).resolveShardIdentifier());
    }

    @Override
    public Optional<ShardIdentifier> resolveShardIdentifier(Persistable<?> persistable) {
        if (persistable.getId() instanceof Id<?> id) {
            return resolveShardIdentifierById(id.getValue());
        }
        if (persistable.getId() instanceof ShardedId<?> sid) {
            return Optional.of(sid.resolveShardIdentifier());
        }
        return Optional.empty();
    }
}
```

### ShardedUUID (value object)

```java
package io.ekbatan.core.shard;

// Value object wrapping a UUID with shard info (group + member) encoded in rand_b.
// Fixed layout: 4-bit group + 8-bit member = 12 shard bits, 62 random bits remaining.
//
// UUID v7 bit layout:
// MSB: [48-bit timestamp][4-bit version=0111][12-bit rand_a]
// LSB: [2-bit variant=10][4-bit group][8-bit member][50-bit random]
public final class ShardedUUID implements ShardAwareId {

    public static final int GROUP_BITS = 4;
    public static final int MEMBER_BITS = 8;
    private static final int SHARD_BITS = GROUP_BITS + MEMBER_BITS;  // 12
    private static final int RANDOM_BITS = 62 - SHARD_BITS;          // 50

    private final UUID value;

    private ShardedUUID(UUID value) {
        this.value = value;
    }

    public UUID value() {
        return value;
    }

    // Create from existing UUID (e.g., loaded from DB)
    public static ShardedUUID from(UUID uuid) {
        return new ShardedUUID(uuid);
    }

    // Generate new UUID v7 with shard bits
    public static ShardedUUID generate(ShardIdentifier shard) {
        long timestamp = System.currentTimeMillis();

        long msb = (timestamp & 0xFFFFFFFFFFFFL) << 16;
        msb |= 0x7000L;
        msb |= (ThreadLocalRandom.current().nextLong() & 0xFFF);

        long shardBits = ((long) shard.group << MEMBER_BITS) | shard.member;
        long randomPart = ThreadLocalRandom.current().nextLong() & ((1L << RANDOM_BITS) - 1);

        long lsb = 0x8000000000000000L;
        lsb |= (shardBits << RANDOM_BITS);
        lsb |= randomPart;

        return new ShardedUUID(new UUID(msb, lsb));
    }

    @Override
    public ShardIdentifier resolveShardIdentifier() {
        long lsb = value.getLeastSignificantBits();
        long shardBits = (lsb >>> RANDOM_BITS) & ((1L << SHARD_BITS) - 1);

        int member = (int) (shardBits & ((1L << MEMBER_BITS) - 1));
        int group = (int) ((shardBits >>> MEMBER_BITS) & ((1L << GROUP_BITS) - 1));

        return ShardIdentifier.of(group, member);
    }
}
```

### ShardedId (independent from Id)

```java
package io.ekbatan.core.domain;

// Independent from Id<T>. Used by sharded models: Model<Wallet, ShardedId<Wallet>, WalletState>
public final class ShardedId<IDENTIFIABLE extends Identifiable<?>>
        implements ShardAwareId, ModelId<UUID>, Comparable<ShardedId<IDENTIFIABLE>> {

    private final ShardedUUID shardedUUID;

    private ShardedId(ShardedUUID shardedUUID) {
        this.shardedUUID = shardedUUID;
    }

    public static <I extends Identifiable<?>> ShardedId<I> of(Class<I> clazz, ShardedUUID shardedUUID) {
        Validate.notNull(clazz, "Identifiable class cannot be null");
        return new ShardedId<>(shardedUUID);
    }

    public static <I extends Identifiable<?>> ShardedId<I> generate(Class<I> clazz, ShardIdentifier shard) {
        return new ShardedId<>(ShardedUUID.generate(shard));
    }

    @Override
    public ShardIdentifier resolveShardIdentifier() {
        return shardedUUID.resolveShardIdentifier();
    }

    @Override
    public UUID getId() {
        return shardedUUID.value();
    }

    public UUID getValue() {
        return shardedUUID.value();
    }

    @Override
    public int compareTo(ShardedId<IDENTIFIABLE> o) {
        return this.shardedUUID.value().compareTo(o.shardedUUID.value());
    }

    @Override
    public String toString() {
        return shardedUUID.value().toString();
    }
}
```

### DatabaseRegistry

```java
package io.ekbatan.core.shard;

public final class DatabaseRegistry {

    private final Map<ShardIdentifier, TransactionManager> transactionManagers;
    public final Map<ShardIdentifier, DSLContext> primary;
    public final Map<ShardIdentifier, DSLContext> secondary;
    public final ShardIdentifier defaultShard;

    private DatabaseRegistry(Builder builder) {
        this.transactionManagers = Map.copyOf(builder.transactionManagers);
        this.defaultShard = Validate.notNull(builder.defaultShard, "defaultShard is required");
        Validate.isTrue(!this.transactionManagers.isEmpty(), "at least one database is required");
        Validate.isTrue(this.transactionManagers.containsKey(this.defaultShard),
                "defaultShard must reference a registered database");

        // Build DSLContext maps from TransactionManagers once
        var p = new HashMap<ShardIdentifier, DSLContext>();
        var s = new HashMap<ShardIdentifier, DSLContext>();
        this.transactionManagers.forEach((id, tm) -> {
            p.put(id, DSL.using(tm.primaryConnectionProvider.getDataSource(), tm.dialect));
            s.put(id, DSL.using(tm.secondaryConnectionProvider.getDataSource(), tm.dialect));
        });
        this.primary = Map.copyOf(p);
        this.secondary = Map.copyOf(s);
    }

    public TransactionManager transactionManager(ShardIdentifier id) {
        var tm = transactionManagers.get(id);
        if (tm == null) {
            throw new IllegalArgumentException("No database registered for shard: " + id);
        }
        return tm;
    }

    public TransactionManager defaultTransactionManager() {
        return transactionManager(defaultShard);
    }

    public static final class Builder {
        private final Map<ShardIdentifier, TransactionManager> transactionManagers = new LinkedHashMap<>();
        private ShardIdentifier defaultShard;

        private Builder() {}
        public static Builder databaseRegistry() { return new Builder(); }

        public Builder withDatabase(ShardIdentifier id, TransactionManager tm) {
            this.transactionManagers.put(id, tm);
            return this;
        }
        public Builder defaultShard(ShardIdentifier defaultShard) {
            this.defaultShard = defaultShard;
            return this;
        }

        public DatabaseRegistry build() { return new DatabaseRegistry(this); }
    }
}
```

### DataSourceConfig (refactored from record to builder)

```java
package io.ekbatan.core.config;

public final class DataSourceConfig {

    public final String jdbcUrl;
    public final String username;
    public final String password;
    public final SQLDialect dialect;
    public final Optional<String> driverClassName;
    public final int maximumPoolSize;
    public final Optional<Integer> minimumIdle;
    public final Optional<Long> idleTimeout;
    public final Optional<Long> leakDetectionThreshold;

    private DataSourceConfig(Builder builder) {
        this.jdbcUrl = Validate.notBlank(builder.jdbcUrl, "jdbcUrl is required");
        this.username = Validate.notBlank(builder.username, "username is required");
        this.password = Validate.notNull(builder.password, "password is required");
        this.dialect = resolveDialect(this.jdbcUrl);
        this.driverClassName = builder.driverClassName;
        this.maximumPoolSize = builder.maximumPoolSize;
        this.minimumIdle = builder.minimumIdle;
        this.idleTimeout = builder.idleTimeout;
        this.leakDetectionThreshold = builder.leakDetectionThreshold;
    }

    private static SQLDialect resolveDialect(String jdbcUrl) {
        if (jdbcUrl.contains("postgresql")) return SQLDialect.POSTGRES;
        if (jdbcUrl.contains("mysql"))      return SQLDialect.MYSQL;
        if (jdbcUrl.contains("mariadb"))    return SQLDialect.MARIADB;
        throw new IllegalArgumentException("Cannot determine dialect from URL: " + jdbcUrl);
    }

    public static final class Builder {
        private String jdbcUrl;
        private String username;
        private String password;
        private Optional<String> driverClassName = Optional.empty();
        private int maximumPoolSize = 10;
        private Optional<Integer> minimumIdle = Optional.empty();
        private Optional<Long> idleTimeout = Optional.empty();
        private Optional<Long> leakDetectionThreshold = Optional.empty();

        private Builder() {}
        public static Builder dataSourceConfig() { return new Builder(); }

        public Builder jdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; return this; }
        public Builder username(String username) { this.username = username; return this; }
        public Builder password(String password) { this.password = password; return this; }
        public Builder driverClassName(String driverClassName) {
            this.driverClassName = Optional.of(driverClassName); return this;
        }
        public Builder maximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize; return this;
        }
        public Builder minimumIdle(int minimumIdle) {
            this.minimumIdle = Optional.of(minimumIdle); return this;
        }
        public Builder idleTimeout(long idleTimeout) {
            this.idleTimeout = Optional.of(idleTimeout); return this;
        }
        public Builder leakDetectionThreshold(long leakDetectionThreshold) {
            this.leakDetectionThreshold = Optional.of(leakDetectionThreshold); return this;
        }

        public DataSourceConfig build() { return new DataSourceConfig(this); }
    }
}
```

### ConnectionProvider (simplified)

```java
// BEFORE:
public static ConnectionProvider hikariConnectionProvider(DataSourceConfig cfg, boolean primary) {
    hikari.setJdbcUrl(cfg.jdbcUrl()
            + (primary ? "?targetServerType=master"
                       : "?targetServerType=preferSlave&loadBalanceHosts=true"));
}

// AFTER:
public static ConnectionProvider hikariConnectionProvider(DataSourceConfig cfg) {
    var hikari = new HikariConfig();
    hikari.setJdbcUrl(cfg.jdbcUrl);    // as-is, user controls everything
    hikari.setUsername(cfg.username);
    hikari.setPassword(cfg.password);
    cfg.driverClassName.ifPresent(hikari::setDriverClassName);
    hikari.setMaximumPoolSize(cfg.maximumPoolSize);
    cfg.minimumIdle.ifPresent(hikari::setMinimumIdle);
    cfg.idleTimeout.ifPresent(hikari::setIdleTimeout);
    cfg.leakDetectionThreshold.ifPresent(hikari::setLeakDetectionThreshold);
    return new ConnectionProvider(new HikariDataSource(hikari));
}
```

### AbstractRepository — shard-aware methods

```java
// New fields
private final DatabaseRegistry databaseRegistry;
private final ShardingStrategy<DB_ID> shardingStrategy;

// --- db() variants ---
protected DSLContext db() {
    return databaseRegistry.primary.get(databaseRegistry.defaultShard);
}

protected DSLContext db(DB_ID id) {
    if (!shardingStrategy.usesShardAwareId()) {
        return db();
    }
    return shardingStrategy.resolveShardIdentifierById(id)
        .map(shardId -> databaseRegistry.primary.get(shardId))
        .orElseGet(this::db);
}

protected DSLContext db(PERSISTABLE p) {
    if (!shardingStrategy.usesShardAwareId()) {
        return db();
    }
    return shardingStrategy.resolveShardIdentifier(p)
        .map(shardId -> databaseRegistry.primary.get(shardId))
        .orElseGet(this::db);
}

protected Collection<DSLContext> dbs() {
    return databaseRegistry.primary.values();
}

// --- readonlyDb() variants ---
protected DSLContext readonlyDb() {
    return databaseRegistry.secondary.get(databaseRegistry.defaultShard);
}

protected DSLContext readonlyDb(DB_ID id) {
    if (!shardingStrategy.usesShardAwareId()) {
        return readonlyDb();
    }
    return shardingStrategy.resolveShardIdentifierById(id)
        .map(shardId -> databaseRegistry.secondary.get(shardId))
        .orElseGet(this::readonlyDb);
}

protected Collection<DSLContext> readonlyDbs() {
    return databaseRegistry.secondary.values();
}

// --- txDb() variants ---
protected Optional<DSLContext> txDb() {
    return databaseRegistry.transactionManager(databaseRegistry.defaultShard)
            .currentTransactionDbContext();
}

protected Optional<DSLContext> txDb(DB_ID id) {
    if (!shardingStrategy.usesShardAwareId()) {
        return txDb();
    }
    var shardId = shardingStrategy.resolveShardIdentifierById(id)
        .orElse(databaseRegistry.defaultShard);
    return databaseRegistry.transactionManager(shardId)
            .currentTransactionDbContext();
}

protected Optional<DSLContext> txDb(PERSISTABLE p) {
    if (!shardingStrategy.usesShardAwareId()) {
        return txDb();
    }
    var shardId = shardingStrategy.resolveShardIdentifier(p)
        .orElse(databaseRegistry.defaultShard);
    return databaseRegistry.transactionManager(shardId)
            .currentTransactionDbContext();
}

// --- txDbElseDb() variants ---
protected DSLContext txDbElseDb() {
    return txDb().orElseGet(this::db);
}

protected DSLContext txDbElseDb(DB_ID id) {
    return txDb(id).orElseGet(() -> db(id));
}

protected DSLContext txDbElseDb(PERSISTABLE p) {
    return txDb(p).orElseGet(() -> db(p));
}
```

### AbstractRepository — base CRUD shard-aware updates

```java
// add() — BEFORE:
public PERSISTABLE add(PERSISTABLE domainObject) {
    txDbElseDb().insertInto(table).set(toRecord(domainObject))...
}

// add() — AFTER:
public PERSISTABLE add(PERSISTABLE domainObject) {
    txDbElseDb(domainObject).insertInto(table).set(toRecord(domainObject))...
}

// update() — BEFORE:
public PERSISTABLE update(PERSISTABLE domainObject) {
    txDbElseDb().update(table).set(record).where(idField.eq(...)).and(versionField.eq(...))...
}

// update() — AFTER:
public PERSISTABLE update(PERSISTABLE domainObject) {
    txDbElseDb(domainObject).update(table).set(record).where(idField.eq(...)).and(versionField.eq(...))...
}

// findById() — BEFORE:
public Optional<PERSISTABLE> findById(ID id) {
    return findOneWhere(idField.eq(id));  // uses db() internally
}

// findById() — AFTER:
public Optional<PERSISTABLE> findById(ID id) {
    return Optional.ofNullable(
            db(id).selectFrom(table)
                .where(idField.eq(id).and(notDeleted()))
                .fetchOne())
            .map(this::fromRecord);
}
```

### WalletRepository — sharded example

```java
// Sharded repository — passes strategy
public class WalletRepository extends ModelRepository<Wallet, WalletsRecord, Wallets, UUID> {
    public WalletRepository(DatabaseRegistry registry, ShardingStrategy strategy) {
        super(Wallet.class, WALLETS, WALLETS.ID, registry, strategy);
    }
}

// Non-sharded repository — no strategy needed
public class ProductRepository extends EntityRepository<Product, ProductsRecord, Products, UUID> {
    public ProductRepository(DatabaseRegistry registry) {
        super(Product.class, PRODUCTS, PRODUCTS.ID, registry);
    }
}
```

### Full Bootstrap — Non-sharded (simple setup)

```java
// Build configs
var primaryCfg = dataSourceConfig()
    .jdbcUrl("jdbc:postgresql://localhost:5432/myapp?targetServerType=master")
    .username("app").password("secret").maximumPoolSize(20).build();

var secondaryCfg = dataSourceConfig()
    .jdbcUrl("jdbc:postgresql://localhost:5432/myapp?targetServerType=preferSlave")
    .username("readonly").password("secret").maximumPoolSize(40).build();

// Build TM + registry (one entry)
var tm = new TransactionManager(
    ConnectionProvider.hikariConnectionProvider(primaryCfg),
    ConnectionProvider.hikariConnectionProvider(secondaryCfg),
    primaryCfg.dialect);

var registry = databaseRegistry()
    .withDatabase(ShardIdentifier.DEFAULT, tm)
    .defaultShard(ShardIdentifier.DEFAULT)
    .build();

// Repositories — same as before, just pass registry instead of TM
var walletRepo = new WalletRepository(registry);
var productRepo = new ProductRepository(registry);
```

### Full Bootstrap — Sharded setup

```java
// Build configs per shard
var hamburgPrimary = dataSourceConfig()
    .jdbcUrl("jdbc:postgresql://eu-hamburg-primary:5432/payments?targetServerType=master")
    .username("app").password("secret").maximumPoolSize(30).build();
var hamburgSecondary = dataSourceConfig()
    .jdbcUrl("jdbc:postgresql://eu-hamburg-replica:5432/payments?targetServerType=preferSlave")
    .username("readonly").password("secret").maximumPoolSize(50).build();

var frankfurtPrimary = dataSourceConfig()
    .jdbcUrl("jdbc:postgresql://eu-frankfurt-primary:5432/payments?targetServerType=master")
    .username("app").password("secret").maximumPoolSize(30).build();
var frankfurtSecondary = dataSourceConfig()
    .jdbcUrl("jdbc:postgresql://eu-frankfurt-replica:5432/payments?targetServerType=preferSlave")
    .username("readonly").password("secret").maximumPoolSize(50).build();

// Build TMs
var hamburgTm = new TransactionManager(
    hikariConnectionProvider(hamburgPrimary), hikariConnectionProvider(hamburgSecondary),
    hamburgPrimary.dialect);
var frankfurtTm = new TransactionManager(
    hikariConnectionProvider(frankfurtPrimary), hikariConnectionProvider(frankfurtSecondary),
    frankfurtPrimary.dialect);

// Build registry
var registry = databaseRegistry()
    .withDatabase(ShardIdentifier.of(0, 0), hamburgTm)
    .withDatabase(ShardIdentifier.of(0, 1), frankfurtTm)
    .defaultShard(ShardIdentifier.of(0, 0))
    .build();

// Strategy
var strategy = EmbeddedBitsShardingStrategy.INSTANCE;

// Repositories
var walletRepo = new WalletRepository(registry, strategy);   // sharded
var productRepo = new ProductRepository(registry);            // not sharded (default shard)

// Execute on a specific shard
var shard = ShardIdentifier.of(0, 1); // Frankfurt
ShardContext.execute(shard, () -> {
    return executor.execute(principal, WalletCreateAction.class, params);
});
```

### ActionExecutor — cross-shard mechanics

```java
// After action.perform(), group plan changes by shard
var result = action.perform(principal, params);

// Group changes by shard using each repository's strategy
var changesByShard = groupChangesByShard(action.plan, repositoryRegistry);

// Enforce single-shard
if (changesByShard.size() > 1 && !executionConfiguration.allowCrossShard) {
    throw new CrossShardException(/* first shard */, /* second shard */);
}

// One transaction per shard — NOT atomic across shards
for (var entry : changesByShard.entrySet()) {
    var shardId = entry.getKey();
    var tm = databaseRegistry.transactionManager(shardId);
    tm.inTransactionChecked(_ -> {
        // Persist domain changes + events for this shard
        changePersister.persistForShard(action, params, actionStartDate, shardId);
    });
}
```

### CrossShardException

```java
package io.ekbatan.core.shard;

public class CrossShardException extends RuntimeException {

    public final ShardIdentifier activeShard;
    public final ShardIdentifier requestedShard;

    public CrossShardException(ShardIdentifier activeShard, ShardIdentifier requestedShard) {
        super("Cross-shard operation detected: action started on shard " + activeShard
              + " but attempted to access shard " + requestedShard);
        this.activeShard = activeShard;
        this.requestedShard = requestedShard;
    }
}
```

### ExecutionConfiguration — allowCrossShard

```java
// New field
public final boolean allowCrossShard;  // default: false

// Builder addition
private boolean allowCrossShard = false;

public Builder allowCrossShard(boolean allowCrossShard) {
    this.allowCrossShard = allowCrossShard;
    return this;
}

// Usage
var config = executionConfiguration()
    .allowCrossShard(true)    // permit multi-shard actions
    .build();

executor.execute(principal, CrossShardAction.class, params, config);
```

## Risks / Trade-offs

### R1: Breaking changes to existing APIs
DataSourceConfig (record → builder), ConnectionProvider (boolean flag removed), AbstractRepository (constructor signature) are breaking changes. All existing repository subclasses and tests must be updated.
**Mitigation:** These are internal framework APIs, not public service interfaces. Migration is mechanical.

### Cross-shard actions are not atomic
With `allowCrossShard=true`, each shard gets its own transaction. Shard A's transaction might commit while shard B's rolls back — partial write with no automatic recovery. Distributed 2PC is a non-goal.
**Mitigation:** Users accept this risk by setting `allowCrossShard=true`. Documentation must be clear about this trade-off.

### EmbeddedBitsShardingStrategy limits shard count
Fixed 4-bit group + 8-bit member = max 16 groups × 256 members. Not configurable — permanent contract.
**Mitigation:** 16 regulatory zones and 256 shards per zone is sufficient for virtually any deployment. Custom strategies can use different layouts.

### R3: Cross-shard reads for non-ID queries
`findAllWhere`, `findAll` cannot route by ID — they need scatter-gather via `dbs()`. This is O(N shards) per query.
**Mitigation:** This is inherent to sharding. Repository authors use `dbs()` explicitly for collection queries. Single-record lookups are O(1).

### R4: ScopedValue per TransactionManager instance
Each TM has its own ScopedValue<Transaction>. With K shards, K ScopedValues exist but only one is active per thread.
**Mitigation:** ScopedValues are extremely lightweight. No performance concern.
