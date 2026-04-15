# Agents Guide

This document provides detailed architectural context for AI agents working on the Ekbatan codebase.

## Project Overview

Ekbatan is a **Java persistence and action framework** built as an alternative to Spring Data. It is a **library**, not a standalone application. Its central idea is that in most business applications, you don't just want to save data — you want to save *what happened* alongside the data itself, atomically, so that the database always tells a complete and consistent story.

The framework is structured around the concept of an **Action** — a unit of business work. An Action doesn't write anything to the database directly. Instead, it declares its intent: what should be created, what should be updated. These declarations accumulate in an **ActionPlan** — a staging area that holds all planned changes without touching the database. Only after the action's logic completes successfully does the **ActionExecutor** step in, open a single database transaction, and flush everything at once: the domain objects to their tables, and the event records to the `eventlog` schema. If any part fails, nothing is written. This all-or-nothing guarantee means the `action_events` table naturally becomes a reliable **outbox** — a stream of committed business facts that can be tailed by a CDC connector or polled and forwarded to Kafka, Pulsar, or any message broker, enabling event-driven architectures without dual-write problems.

The framework draws a deliberate line between two kinds of persistent objects. A **Model** is a domain aggregate whose changes matter — every mutation produces a `ModelEvent` that gets persisted in the eventlog alongside the action that caused it. You extend `Model` when downstream systems need to know what happened: a wallet was created, money was deposited, an order was shipped. An **Entity** is simpler — it gets persisted and version-tracked, but its changes are not recorded as events. You extend `Entity` for supporting data where the history of individual mutations is irrelevant: lookup tables, configuration records, auxiliary references that participate in an action but don't need their own event trail.

Both `Model` and `Entity` are **immutable**. A deposit on a wallet doesn't mutate the wallet — it returns a new wallet instance with the updated balance and a `WalletMoneyDepositedEvent` attached. This immutability is not incidental; it's what makes the two-phase execution model safe. During the `perform` phase, the action can read from repositories and construct new states freely, attaching events as it goes. Nothing is committed, so there's no risk of partial writes. The ActionPlan simply collects references to these immutable snapshots. When the executor flushes, it knows exactly what the final state should be.

**Optimistic locking** is baked into every persistable object through a mandatory `version` column. The framework never acquires row-level locks. Instead, every update carries a `WHERE version = ?` clause. If another transaction modified the same row in the meantime, the update affects zero rows and a `StaleRecordException` is thrown, unwinding the entire transaction. This makes the system safe under concurrency without pessimistic locking overhead, and keeps the write path simple and predictable.

The repository layer is built directly on **JOOQ** rather than JPA or Hibernate. There is no object-relational mapping magic, no lazy loading, no session cache. Each repository explicitly defines how to convert between a domain object and a JOOQ record (`fromRecord` / `toRecord`), making the persistence boundary visible and debuggable. The framework handles dialect differences (PostgreSQL, MySQL, MariaDB) internally — different SQL strategies for batch updates, different type representations for UUIDs and JSON — so that application code stays database-agnostic while the SQL remains correct and efficient.

## Module Structure

```
ekbatan/
├── ekbatan-core/                          # Core framework library
│   ├── src/main/java/io/ekbatan/core/
│   │   ├── domain/                        # Domain abstractions (Model, Entity, ModelEvent, Id, TypedValue)
│   │   ├── repository/                    # Repository pattern (AbstractRepository, ModelRepository, EntityRepository)
│   │   ├── action/                        # Action/Command pattern (Action, ActionExecutor, ActionPlan)
│   │   │   └── persister/                 # Change & event persistence
│   │   │       └── event/single_table_json/  # Single-table JSON event store implementation
│   │   ├── shard/                         # Sharding: ShardIdentifier, DatabaseRegistry, ShardingStrategy
│   │   ├── persistence/                   # Transaction management, JOOQ converters
│   │   └── config/                        # DataSourceConfig
├── ekbatan-annotation-processor/          # @AutoBuilder annotation processor (JavaPoet)
├── ekbatan-integration-tests/              # End-to-end integration tests (package: io.ekbatan.test)
│   ├── core-repo/                         # Testcontainer-based repository tests across PG/MySQL/MariaDB
│   │   ├── shared/                        # Shared BaseRepositoryTest, dummy models (test fixtures)
│   │   ├── pg/                            # PostgreSQL runners (repository, denormalized-events)
│   │   ├── mysql/                         # MySQL runners
│   │   └── mariadb/                       # MariaDB runners
│   ├── postgres-simple/                   # Simple end-to-end wallet flow (PostgreSQL)
│   ├── postgres-sharded/                  # Sharded wallet with cross-shard tests (PostgreSQL)
│   └── event-pipeline/                    # Debezium → Kafka JSON/Avro/Protobuf event streaming
├── buildSrc/                              # Gradle build conventions
└── config/checkstyle/                     # Checkstyle rules
```

## Architecture Layers

### Domain Layer (`ekbatan-core/...core/domain/`)

The foundation. Two kinds of domain objects:

- **`Model`** — Event-sourced aggregate with `id`, `state`, `version`, `events`, `createdDate`, `updatedDate`. Immutable. Business methods return new instances via builders. Events are accumulated on the model instance.
- **`Entity`** — Simpler stateful object with `id`, `state`, `version`. No events. Immutable.
- **`ModelEvent<M>`** — Base class for domain events. Carries `modelId` and `modelName`. Serializable.
- **`Id<T extends Identifiable>`** — Type-safe UUID wrapper. Prevents mixing IDs of different types at compile time.
- **`TypedValue<T>`** — Base value object wrapper for type safety.
- **`Persistable`** — Interface adding `version` and `nextVersion()` for optimistic locking.
- **`GenericState`** — Default state enum (ACTIVE, DELETED) for entities without custom states.

Key pattern: Domain objects are **immutable**. Mutations create new instances. Models accumulate events during mutations (e.g., `wallet.deposit(amount)` returns a new Wallet with a `WalletMoneyDepositedEvent` attached).

### Repository Layer (`ekbatan-core/...core/repository/`)

JOOQ-based persistence:

- **`AbstractRepository`** — Core implementation with full CRUD, optimistic locking (version field), soft deletion (filters DELETED state), dialect-aware SQL for MySQL vs MariaDB vs PostgreSQL, and shard-aware routing (scatter-gather reads, ID-based writes, batch single-shard validation).
- **`ModelRepository`** — Adds `created_date`/`updated_date` field validation.
- **`EntityRepository`** — Simpler variant for entities.
- **`RepositoryRegistry`** — Type-safe map of `Class -> Repository` with builder pattern.

Subclasses must implement `fromRecord(RECORD)` and `toRecord(PERSISTABLE)` to map between JOOQ records and domain objects.

Exception hierarchy in `repository/exception/`:
- `PersistenceException` — Rich hierarchy with `ModelAware`, `ConstraintViolation` interfaces
- `StaleRecordException` — Optimistic locking failure
- `EntityNotFoundException` — Lookup miss

### Action Layer (`ekbatan-core/...core/action/`)

Command pattern for business operations:

- **`Action<PARAM, RESULT>`** — Base class. Has an `ActionPlan`. Subclasses implement `perform(principal, params)`.
- **`ActionPlan`** — Accumulates additions and updates to domain objects during action execution.
- **`ActionExecutor`** — Orchestrates: get action from registry -> call perform -> wrap in transaction -> persist changes.
- **`ActionRegistry`** — Maps action classes to suppliers (factories).
- **`ChangePersister`** — Iterates plan changes, extracts events from models, persists via repositories + event persister.
- **`EventPersister`** — Interface for event storage strategy.
- **`SingleTableJsonEventPersister`** — Default implementation using a single `eventlog.events` table with JSONB payload.

### Persistence Layer (`ekbatan-core/...core/persistence/`)

- **`TransactionManager`** — Uses Java 21+ `ScopedValue<Transaction>` for thread-safe transaction context. Provides `inTransaction()` and `inTransactionChecked()`.
- **`ConnectionProvider`** — HikariCP wrapper. Supports primary (master) and secondary (read-replica) connections.
- **`Transaction`** — Wraps a JDBC Connection with begin/commit/rollback lifecycle.
- **JOOQ Converters** — `InstantConverter`, `JSONObjectNodeConverter`, `JSONBObjectNodeConverter`, `UuidBinaryConverter`, `UuidStringConverter`.

### Annotation Processor (`ekbatan-annotation-processor/`)

- **`@AutoBuilder`** — Marks a Model or Entity class for compile-time builder generation.
- **`AutoBuilderProcessor`** — Uses JavaPoet to generate builder classes that extend `Model.Builder` or `Entity.Builder`. Generated builders have fluent setters, getters, and a `build()` method.

## Key Design Patterns & Conventions

### Immutability
All domain objects are immutable. State changes produce new instances. Example:
```java
Wallet updated = wallet.deposit(amount); // returns NEW wallet with event attached
plan.update(updated);                    // registers for persistence
```

### Single-Threaded Action Execution
Actions execute single-threaded. Do not spawn concurrent threads inside `Action.perform()`. The `TransactionManager` uses `ScopedValue` to bind transaction context, and the underlying JDBC `Connection` is not thread-safe. Concurrent access from child threads would share that connection unsafely. Users needing parallel work should split it into separate action executions or handle concurrency at a layer above the framework. If data from multiple sources needs to be read in parallel, do so before executing the action — fan out the reads, join/aggregate the results, build the action's `Params` from the aggregated data, then execute the action. This keeps actions focused on declaring business intent, with parallelism handled by the caller.

### No Composable / Nested Actions
Actions must **not** invoke other actions within their `perform()` method. The framework intentionally does not support nested or composable action execution. An action is a self-contained unit of business work that produces a single atomic transaction — nesting actions blurs transaction boundaries, creates hidden coupling between business operations, and makes the execution flow harder to reason about. If two operations need to happen together, they belong in a single action. If they are independent, execute them separately from the caller. If one must follow the other, orchestrate that sequence at the service/application layer above the framework.

### Optimistic Locking
Every persistable has a `version` field. On update, the repository checks that the version matches. If not, `StaleRecordException` is thrown. `nextVersion()` increments the version.

### Soft Deletion
Records are never physically deleted. The `state` field is set to `DELETED`. Queries automatically filter out deleted records.

### Idempotency
Ekbatan does **not** provide a framework-level idempotency key abstraction (no dedicated column on `action_events`, no `idempotencyKey` field on action params). The framework's atomic transactions already guarantee that if a unique constraint fails, the entire transaction rolls back — no partial writes. Action designers who need idempotency choose their own domain-appropriate field name (e.g., `referenceId`, `transactionId`, `depositId`) and enforce uniqueness via a constraint on their domain table. This keeps the framework simple and avoids prescribing a single field/column name that may not fit every domain context.

### Event Sourcing (Single-Table, JSON)
Models accumulate `ModelEvent` instances. When persisted via `ChangePersister`, events are extracted and stored in a single denormalized table:
- `eventlog.events` — One row per emitted event, carrying action metadata (action_id, action_name, action_params), model metadata (model_id, model_type, event_type), and a JSONB `payload`. One sentinel row per action (no event_type) captures actions that emit zero events.

### Event IDs and Sharding
Only domain entity IDs (e.g., `wallets.id`) use `ShardedUUID` for shard routing. `eventlog.events.id` is a regular UUID, co-located with the action/model it describes — for cross-shard actions (when `allowCrossShard=true`), rows are written to each involved shard so that every shard has a self-contained picture. No cross-shard foreign keys.

### Sharding

Ekbatan supports horizontal sharding with a two-level hierarchy: **group** (business/regulatory constraint, e.g., "Mexico data stays in Mexico") and **member** (performance scaling within a group).

#### Core Concepts

- **`ShardIdentifier(int group, int member)`** — Numeric shard address. `DEFAULT = (0, 0)`. Fixed 8-bit group + 6-bit member, supporting up to 256 groups x 64 members.
- **`ShardedUUID`** — UUID v7 with shard bits (group + member) encoded in `rand_b`. Self-describing: the shard can be decoded from the UUID without any lookup.
- **`ShardedId<T>`** — Type-safe ID wrapper around `ShardedUUID`, independent from `Id<T>`. Implements `ShardAwareId`.
- **`DatabaseRegistry`** — Unified entry point for all database access. Maps `ShardIdentifier → TransactionManager`. Provides `primary`/`secondary` DSLContext maps and `effectiveShard()` for logical-to-physical shard mapping.
- **`ShardingStrategy<DB_ID>`** — Pluggable interface on `Repository`. `NoShardingStrategy` (default, zero impact) and `EmbeddedBitsShardingStrategy` (decodes shard from UUID v7 bits).
- **`CrossShardException`** — Thrown when a batch or action spans multiple shards without `allowCrossShard=true`.

#### Shard Routing in AbstractRepository

AbstractRepository resolves shards via `effectiveShard()` overloads, which combine strategy resolution with `DatabaseRegistry.effectiveShard()` for logical-to-physical mapping. This means unregistered shards (e.g., Australia using group 2 when only default and Mexico are deployed) automatically fall back to the default shard.

**DB access methods** — four tiers, each with overloads for `()`, `(DB_ID)`, `(PERSISTABLE)`, `(ShardIdentifier)`:
- `db()` / `readonlyDb()` — primary/secondary DSLContext
- `txDb()` — current transaction context (returns `Optional`)
- `txDbElseDb()` — transaction context or fallback to non-transactional
- `dbs()` / `readonlyDbs()` — all shard contexts (strategy-aware: returns only default for `NoShardingStrategy`)

**Write routing** — single-entity methods use `effectiveShard(domainObject)`. Batch methods use `effectiveShard(Collection)` which validates all entities resolve to the same effective shard or throws `CrossShardException`.

**Read routing** — ID-based methods (`findById`, `existsById`) route to the specific shard via `effectiveShard(id)`. Condition-based methods (`findAll`, `findAllWhere`, `count`, `countWhere`, `findOneWhere`, `existsWhere`) scatter-gather across `dbs()`. `findAllByIds` groups IDs by shard and queries each shard with its subset.

**Dialect resolution** — each CRUD method resolves dialect from the target shard via `dialect(shard)` rather than assuming a global dialect, so mixed-dialect setups are theoretically supported.

**No offset/limit methods** — `findAll(offset, limit)` and `findAllWhere(condition, offset, limit)` are intentionally not provided. Offset/limit pagination doesn't work correctly with scatter-gather. Sharded systems should use cursor-based (keyset/temporal) pagination, implemented in concrete repository subclasses.

**ID-based strategy guard** — `rejectNonIdBasedStrategy()` is centralized inside `effectiveShard(DB_ID)`. It allows `NoShardingStrategy` through (no constraint needed) and rejects custom strategies that don't support `usesShardAwareId()`.

#### Cross-Shard Enforcement in ActionExecutor

`ActionExecutor.persistChanges()` groups plan changes by shard using each repository's `shardingStrategy()`. If changes span multiple shards and `ExecutionConfiguration.allowCrossShard` is `false` (the default), `CrossShardException` is thrown. When allowed, each shard gets its own transaction, and action events are duplicated to all involved shards with the same UUID.

### Timezone Convention — Always UTC
All timestamps in Ekbatan should be stored and processed in **UTC**. This applies to:

- **Database server timezone** — set the database machine or container to `TZ=UTC`. Ekbatan uses `java.time.Instant` (always UTC) for `created_date` and `updated_date` fields. If the database server runs in a non-UTC timezone, `TIMESTAMP` columns (without time zone) will silently shift values on read, causing mismatches between what Java wrote and what the database returns.
- **Table column values** — all timestamps persisted by the framework represent UTC instants. Do not store local times. If a business requirement needs a local time representation, store it as a separate field alongside the UTC instant.
- **TestContainers** — always configure with `.withEnv("TZ", "UTC")` to avoid timezone-dependent test failures (e.g., daylight saving time shifts).
- **JDBC connections** — when connecting to PostgreSQL, MySQL, or MariaDB, ensure the session timezone is UTC. For PostgreSQL this is typically the default; for MySQL/MariaDB, consider adding `serverTimezone=UTC` to the JDBC URL if needed.

### Multi-Database Support
The framework supports PostgreSQL, MySQL, and MariaDB. Dialect differences are handled in:
- `AbstractRepository` — Different SQL strategies for upsert/update
- JOOQ converters — `JSONB` (PostgreSQL) vs `JSON` (MySQL/MariaDB)
- UUID handling — Native UUID (PostgreSQL) vs binary/string (MySQL)
- Test infrastructure — Separate test subprojects per database

### OpenTelemetry Tracing

Ekbatan instruments its action execution pipeline using the **OpenTelemetry API** (`opentelemetry-api`). The library depends only on the API — no SDK. When no OTel SDK is registered at runtime, all tracing calls are no-ops with zero overhead. Consumers bring their own `opentelemetry-sdk` and exporters.

**Instrumentation scope:** `io.ekbatan.core` version `1.0.0`, obtained from `GlobalOpenTelemetry.get().getTracer(...)`.

**Span hierarchy:**
```
[ekbatan.action.execute]                    ActionExecutor.execute()
├── [ekbatan.action.perform]                Action.perform()
└── [ekbatan.action.persist]                ActionExecutor.persistChanges()
    └── [ekbatan.transaction]               TransactionManager.inTransactionChecked() (per shard)
        ├── [ekbatan.repository]            AbstractRepository addAllNoResult/updateAllNoResult
        └── [ekbatan.event.persist]         EventPersister.persistActionEvents()
```

**Attributes:**

| Attribute | Type | Span | Description |
|---|---|---|---|
| `ekbatan.action.name` | string | action.execute | Simple class name of the action |
| `ekbatan.action.principal` | string | action.execute | Principal name |
| `ekbatan.action.outcome` | string | action.execute | `"success"` or `"error"` |
| `ekbatan.action.retry.count` | long | action.execute | Total retries (0 if none) |
| `ekbatan.shard.cross_shard` | boolean | action.persist | Present when changes span multiple shards |
| `ekbatan.shard.group` | long | transaction | Shard group identifier |
| `ekbatan.shard.member` | long | transaction | Shard member identifier |
| `db.operation.name` | string | repository | `"INSERT"` or `"UPDATE"` |
| `ekbatan.entity.type` | string | repository | Simple class name of the domain object |
| `ekbatan.batch.size` | long | repository | Number of records in the batch |
| `ekbatan.event.count` | long | event.persist | Number of model events persisted |

**Retry events:** Each retry attempt adds a span event named `"retry"` to the action span with attributes `retry.attempt` (int) and `retry.exception` (exception class name).

**Error handling:** On failure, spans are marked with `StatusCode.ERROR` and the exception is recorded via `span.recordException()`.

**Context propagation:** Since actions execute single-threaded (ScopedValue-based transactions), context flows naturally via `Span.makeCurrent()` / `Scope`. No async context passing is needed. Each `TransactionManager` instance knows its own `ShardIdentifier` (set at construction time, defaults to `ShardIdentifier.DEFAULT`), so `inTransactionChecked()` automatically sets shard attributes on the transaction span without requiring the shard to be passed per-call.

### Builder Pattern

The project uses the Builder pattern extensively. There are two categories: **infrastructure builders** (for framework classes like `ActionExecutor`, `ExecutionConfiguration`, registries) and **domain builders** (for `Model` and `Entity` subclasses). Both follow the same core principles but differ in structure.

#### Core Principles

1. **Target class fields are `public final`** — the target class is immutable, so fields are exposed directly. No getters needed. Builder fields are `private` — they are internal to the builder.
2. **Builder is a `static final class` nested inside the target class** (or a separate generated class for `@AutoBuilder` domain objects).
3. **Private constructor on the target class** — only the Builder can instantiate it. The constructor takes the Builder as its sole argument.
4. **Private constructor on the Builder** — instantiation goes through a static factory method.
5. **Static factory method** — named after the thing being built, returns a new Builder instance.
6. **Setter methods just assign** — no validation, and no guards on prior state (e.g. "already called", "call order"). Builders are often returned partially-configured from factories, and a downstream caller may legitimately override an earlier value — restricting that breaks composability. Last call wins, same as repeated `Map.put`. Setters return `this` (or `self()` for generic builders) for fluent chaining.
7. **All validation happens in the target class constructor** — the constructor reads builder fields and validates using `Validate.notNull()`, `Validate.isTrue()`, etc. This is the single place where invariants are enforced.
8. **Default values are set on the builder field declaration** — not resolved with ternary logic in the constructor. If a field has a sensible default, assign it at the field level in the Builder. The constructor then just reads it directly.
9. **Fields with dependent defaults** — when a default depends on other builder state (another field, or the final set of registered entries at build time), the default cannot be set at the field level. In this case, declare the builder field as `Optional<T>` initialized to `Optional.empty()`, and resolve it in the constructor with `orElseGet(...)`. Avoid raw `null` in builder fields — express absence explicitly with `Optional`.

#### Infrastructure Builder Example

```java
public final class Foo {

    public final Bar bar;                       // required field
    public final Baz baz;                       // required field
    public final int maxSize;                   // primitive with default
    public final Optional<Retry> retryConfig;         // optional field
    public final List<String> tags;             // collection with default
    public final Qux qux;                       // field with dependent default

    private Foo(Builder builder) {
        // Required fields — validate in constructor
        this.bar = Validate.notNull(builder.bar, "bar is required");
        this.baz = Validate.notNull(builder.baz, "baz is required");

        // Primitive with default — just assign (default set on builder field)
        this.maxSize = builder.maxSize;

        // Optional — just assign (default set on builder field)
        this.retryConfig = builder.retryConfig;

        // Collection with default — just assign (default set on builder field)
        this.tags = List.copyOf(builder.tags);

        // Dependent default — resolve here because it depends on another field
        this.qux = builder.qux.orElseGet(() -> new DefaultQux(this.bar));
    }

    public static final class Builder {
        // Required fields — no default, left null
        private Bar bar;
        private Baz baz;

        // Primitive with default — set at field level
        private int maxSize = 100;

        // Optional field — use Optional, set default at field level
        private Optional<Retry> retryConfig = Optional.of(StaleRecordFixedRetry.DEFAULT);

        // Collection with default — initialize at field level
        private List<String> tags = new ArrayList<>();

        // Dependent default — Optional.empty() until set, resolved in constructor
        private Optional<Qux> qux = Optional.empty();

        private Builder() {}

        public static Builder foo() {
            return new Builder();
        }

        // Setter for required object field — just assign, no validation
        public Builder bar(Bar bar) {
            this.bar = bar;
            return this;
        }

        public Builder baz(Baz baz) {
            this.baz = baz;
            return this;
        }

        // Setter for primitive field
        public Builder maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        // Setter for Optional field — wraps in Optional.of()
        public Builder retryConfig(Retry retryConfig) {
            this.retryConfig = Optional.of(retryConfig);
            return this;
        }

        // Explicit "unset" method for Optional field — sets to Optional.empty()
        public Builder noRetry() {
            this.retryConfig = Optional.empty();
            return this;
        }

        // Setter for collection — replaces the list
        public Builder tags(List<String> tags) {
            this.tags = new ArrayList<>(tags);
            return this;
        }

        // Additive method for collection — uses "with" prefix
        public Builder withTag(String tag) {
            this.tags.add(tag);
            return this;
        }

        // Setter for field with dependent default — wraps in Optional.of()
        public Builder qux(Qux qux) {
            this.qux = Optional.of(qux);
            return this;
        }

        public Foo build() {
            return new Foo(this);
        }
    }
}
```

#### Field Type Rules

| Field type | Builder field type | Builder default | Setter pattern | Constructor handling |
|---|---|---|---|---|
| Required object | `T` | `null` (no default) | `this.field = value` | `Validate.notNull(builder.field, "...")` |
| Required primitive | `int`, `long`, etc. | explicit default or `0` | `this.field = value` | Additional validation if needed |
| Optional object | `Optional<T>` | `Optional.of(default)` or `Optional.empty()` | `this.field = Optional.of(value)` | `this.field = builder.field` |
| Collection | `List<T>` | `new ArrayList<>()` | Replace: `new ArrayList<>(value)`, Add: `this.list.add(value)` | `List.copyOf(builder.field)` |
| Field with dependent default | `Optional<T>` | `Optional.empty()` | `this.field = Optional.of(value)` | `builder.field.orElseGet(() -> computeDefault())` |

#### Naming Conventions for Builder Methods

- **Static factory**: named after the thing being built — `foo()`, `actionExecutor()`, `executionConfiguration()`
- **Setter**: named after the field — `bar(Bar bar)`, `maxSize(int maxSize)`
- **Additive (collections)**: `with` prefix — `withTag(String tag)`, `withEvent(ModelEvent event)`, `withAction(Class action, Supplier supplier)`
- **Replace (collections)**: named after the field — `tags(List<String> tags)`, `events(List<ModelEvent> events)`
- **Unset (Optional)**: `no` prefix — `noRetry()`

#### Domain Builder (Model/Entity)

Domain builders for `Model` and `Entity` subclasses differ from infrastructure builders in structure:

- They extend `Model.Builder<ID, B, M, STATE>` or `Entity.Builder<ID, B, E, STATE>` — abstract generic base classes that provide `id`, `state`, `version`, and (for Model) `events`, `createdDate`, `updatedDate`.
- They use the **`self()` pattern** for fluent chaining across the generic hierarchy. Each setter in the base class returns `self()` (which casts `this` to the concrete builder type `B`) instead of `this`, so the return type stays as e.g. `WalletBuilder` rather than falling back to `Model.Builder`.
- The domain class constructor takes its builder as the sole argument, calls `super(builder)` to initialize base fields, then reads and validates domain-specific fields from the builder.
- Domain classes also define a `copy()` method that creates a builder pre-populated with the current state, enabling immutable updates.
- All the same core principles apply: `public final` fields on the domain class, validation in the constructor, defaults on builder field declarations, setters just assign.

```java
// Domain class
public final class Wallet extends Model<Wallet, Id<Wallet>, WalletState> {

    public final UUID ownerId;
    public final Currency currency;
    public final BigDecimal balance;

    Wallet(WalletBuilder builder) {
        super(builder);
        this.ownerId = Validate.notNull(builder.ownerId, "ownerId cannot be null");
        this.currency = Validate.notNull(builder.currency, "currency cannot be null");
        this.balance = Validate.notNull(builder.balance, "balance cannot be null");
    }

    // Creation factory method — the "official" way to create a new Wallet.
    // Named createXxx() where Xxx matches the class name, so it reads clearly with static imports:
    //     import static ...Wallet.createWallet;
    //     createWallet(ownerId, currency, balance);
    // This method sets up id, state, initial version, AND emits the creation event.
    // Use this ONLY when you want the full creation ceremony including the event.
    // If you need a Wallet instance WITHOUT the creation event (e.g., test fixtures
    // simulating a model already loaded from the DB), use WalletBuilder.wallet()
    // (or Wallet.Builder.wallet() if the builder is a nested class) directly.
    public static WalletBuilder createWallet(UUID ownerId, Currency currency, BigDecimal balance) {
        final var id = Id.random(Wallet.class);
        return WalletBuilder.wallet()
                .id(id)
                .state(OPENED)
                .ownerId(ownerId)
                .currency(currency)
                .balance(balance)
                .withInitialVersion()
                .withEvent(new WalletCreatedEvent(id, ownerId, currency, balance));
    }

    // copy() enables immutable updates
    @Override
    public WalletBuilder copy() {
        return WalletBuilder.wallet()
                .copyBase(this)
                .ownerId(ownerId)
                .currency(currency)
                .balance(balance);
    }

    // Business method — returns a new immutable instance
    public Wallet deposit(BigDecimal amount) {
        return copy()
                .balance(balance.add(amount))
                .increaseVersion()
                .withEvent(new WalletMoneyDepositedEvent(id, amount))
                .build();
    }
}

// Domain builder — extends Model.Builder
public final class WalletBuilder extends Model.Builder<Id<Wallet>, WalletBuilder, Wallet, WalletState> {

    UUID ownerId;
    Currency currency;
    BigDecimal balance;

    private WalletBuilder() {}

    public static WalletBuilder wallet() {
        return new WalletBuilder();
    }

    public WalletBuilder ownerId(UUID ownerId) {
        this.ownerId = ownerId;
        return this;
    }

    public WalletBuilder currency(Currency currency) {
        this.currency = currency;
        return this;
    }

    public WalletBuilder balance(BigDecimal balance) {
        this.balance = balance;
        return this;
    }

    @Override
    public Wallet build() {
        return new Wallet(this);
    }
}
```

Note that domain builder fields are **package-private** (not `private`), because the domain class in the same package reads them directly in its constructor.

**Inherited methods from `Model.Builder`:** `id()`, `state()`, `version()`, `withInitialVersion()`, `increaseVersion()`, `withEvent()`, `events()`, `createdDate()`, `updatedDate()`, `copyBase()`, `self()`, and abstract `build()`.

**Inherited methods from `Entity.Builder`:** `id()`, `state()`, `version()`, `withInitialVersion()`, `increaseVersion()`, `copyBase()`, `self()`, and abstract `build()`. Entity has no events, createdDate, or updatedDate.

#### @AutoBuilder (Code Generation)

For domain classes (`Model` and `Entity` subclasses), writing the builder by hand is repetitive — the builder just mirrors the domain class fields with setters and getters. The `@AutoBuilder` annotation processor eliminates this boilerplate by generating the builder class at compile time.

**When to use `@AutoBuilder`:** Use it for standard domain classes where the builder is a straightforward mirror of the domain fields. If a domain builder needs custom logic (conditional defaults, computed fields, specialized setter behavior), write the builder manually instead.

**How it works:**

1. Annotate the domain class with `@AutoBuilder`:
   ```java
   @AutoBuilder
   public final class Wallet extends Model<Wallet, Id<Wallet>, WalletState> {
       public final UUID ownerId;
       public final Currency currency;
       public final BigDecimal balance;
       public final List<String> aliases;

       Wallet(WalletBuilder builder) {
           super(builder);
           this.ownerId = Validate.notNull(builder.ownerId, "ownerId cannot be null");
           this.currency = Validate.notNull(builder.currency, "currency cannot be null");
           this.balance = Validate.notNull(builder.balance, "balance cannot be null");
           this.aliases = Objects.requireNonNullElse(builder.aliases, List.of());
       }

       // ... createWallet(), copy(), business methods ...
   }
   ```

2. The annotation processor generates a `WalletBuilder` class at compile time (in `build/generated/sources/annotationProcessor/`):
   ```java
   @Generated("io.ekbatan.core.processor.AutoBuilderProcessor")
   public final class WalletBuilder extends Model.Builder<Id<Wallet>, WalletBuilder, Wallet, WalletState> {
       UUID ownerId;
       Currency currency;
       BigDecimal balance;
       List<String> aliases;

       private WalletBuilder() {}

       public static WalletBuilder wallet() { return new WalletBuilder(); }

       public WalletBuilder ownerId(UUID ownerId) { this.ownerId = ownerId; return this; }
       public UUID ownerId() { return this.ownerId; }

       public WalletBuilder currency(Currency currency) { this.currency = currency; return this; }
       public Currency currency() { return this.currency; }

       // ... same for balance, aliases ...

       @Override
       public Wallet build() { return new Wallet(this); }
   }
   ```

**What @AutoBuilder generates:**

- A `final` class named `<ModelName>Builder` extending `Model.Builder` or `Entity.Builder` with the correct type parameters.
- **Package-private fields** for each non-static field declared in the domain class (not inherited fields — those come from the base builder).
- **Fluent setter** for each field — assigns the value and returns `this`.
- **Getter** for each field — returns the current value.
- **Private constructor** — enforces instantiation through the static factory method.
- **Static factory method** — named after the domain class in lowercase (e.g., `wallet()` for `Wallet`, `product()` for `Product`).
- **`build()` override** — calls `new ModelClass(this)`.
- **`@Generated` annotation** — marks the class as processor-generated.

**What @AutoBuilder does NOT generate:**

- The domain class itself — you write that manually.
- The domain class constructor — you write that with validation logic.
- Business methods (`deposit()`, `withdraw()`, `delete()`) — those live on the domain class.
- The `copy()` method — you write that on the domain class.
- Factory methods (`createWallet()`) — you write those on the domain class.
- Any custom setter behavior — if you need special setters, write the builder manually.

**Constraints:**

- The annotated class must directly extend `Model` or `Entity`.
- The annotation has source retention (`@Retention(RetentionPolicy.SOURCE)`) — it is erased from compiled bytecode.
- Generated setters do not deep-copy mutable fields (e.g., `List`). If the domain class needs defensive copies, handle it in the domain class constructor.
- The generated builder follows all standard builder conventions: package-private fields, private constructor, static factory, setters just assign, no validation.

### Naming Conventions
- Java: `camelCase` fields, `PascalCase` classes
- SQL: `snake_case` columns and tables
- Packages: `lowercase` with underscores for multi-word directories (e.g., `denormalized`)
- Actions: `Model[Verb]Action` — e.g., `WalletCreateAction`, `WalletDepositMoneyAction` (not `CreateWalletAction`)
- Commit messages: `EKB-XXXX` ticket prefix

## Testing

### Test Infrastructure
- **`BaseRepositoryTest`** — Large abstract test class in `ekbatan-integration-tests/core-repo/shared` covering all CRUD operations, optimistic locking, soft deletion
- **`BaseShardedRepositoryTest`** — Abstract test class covering shard-aware operations: scatter-gather reads, ID-based routing, cross-shard batch rejection, shard isolation
- **`Dummy` model** — Test-only model mirroring the Wallet structure
- **Database-specific runners** — Separate subprojects for PostgreSQL, MySQL, MariaDB using TestContainers
- **Flyway migrations** — Per-database test migrations in each test subproject

### Test Stack
- JUnit 5 (Jupiter)
- Mockito (mocking in ekbatan-core), MockK (mocking in core-repo tests)
- AssertJ (fluent assertions)
- JsonUnit (JSON assertions)
- TestContainers (database containers)

### Test Structure — GIVEN / WHEN / THEN

All tests use `// GIVEN`, `// WHEN`, `// THEN` comments to separate setup, action, and assertion phases. This makes the test's intent immediately clear. Combine or extend phases as appropriate:

```java
// Standard form
@Test
void should_create_wallet() throws Exception {
    // GIVEN
    var clock = new VirtualClock();
    clock.pauseAt(Instant.parse("2025-01-01T00:00:00Z"));

    // WHEN
    var result = ActionSpec.of(new CreateAction(clock))
            .withPrincipal(() -> "user")
            .execute(new CreateAction.Params("wallet"));

    // THEN
    result.assertAdded(Wallet.class, w -> {
        assertThat(w.name).isEqualTo("wallet");
    });
}

// Combined GIVEN / WHEN — when setup and action are trivially interleaved
@Test
void constructor_rejects_null() {
    // GIVEN / WHEN / THEN
    assertThatThrownBy(() -> new Foo(null))
            .isInstanceOf(NullPointerException.class);
}

// Combined WHEN / THEN — when action and assertion are a single expression
@Test
void returns_empty_for_unknown_type() {
    // GIVEN
    var registry = repositoryRegistry().build();

    // WHEN / THEN
    assertThat(registry.repository(Wallet.class)).isNull();
}

// Multiple rounds — when a test exercises a sequence of actions
@Test
void retry_recovers_after_transient_failure() throws Exception {
    // GIVEN
    var attempts = new AtomicInteger(0);
    var retry = Retry.with(Map.of(IllegalStateException.class, new RetryConfig(2, Duration.ZERO)));

    // WHEN
    var result = retry.execute(() -> {
        if (attempts.incrementAndGet() <= 1) throw new IllegalStateException("transient");
        return "ok";
    });

    // THEN
    assertThat(result).isEqualTo("ok");

    // AND
    assertThat(attempts.get()).isEqualTo(2);
}
```

**Rules:**
- Every test must have at least `// GIVEN / WHEN / THEN` or `// GIVEN`, `// WHEN`, `// THEN` as separate comments.
- Use `// AND` for additional assertions that verify a different aspect of the result.
- Use `// GIVEN / WHEN / THEN` (combined on one line) only for single-expression tests like null-rejection checks.
- Use `// WHEN / THEN` when the action and assertion are a single fluent call.
- Phases can repeat (`// WHEN`, `// THEN`, `// AND`, `// WHEN`, `// THEN`) for multi-step scenarios.

### Running Tests
```bash
./gradlew test                                    # All tests
./gradlew :ekbatan-integration-tests:core-repo:pg:repository:test    # PostgreSQL only
./gradlew :ekbatan-integration-tests:core-repo:mysql:repository:test # MySQL only
./gradlew :ekbatan-integration-tests:core-repo:mariadb:repository:test # MariaDB only
./gradlew :ekbatan-integration-tests:test          # Integration tests
```

## Build & Tooling

- **Java 25** — Uses records, ScopedValue, modern features
- **Gradle** (Kotlin DSL) — Multi-project build
- **Spotless** — Palantir Java Format 2.81.0, auto-applied before build
- **Checkstyle** — Custom rules in `config/checkstyle/checkstyle.xml`
- **JOOQ Docker Plugin** — Generates type-safe SQL classes from Flyway migrations via Docker
- **Dependency versions** — Centralized in `gradle.properties`

### Build Commands
```bash
./gradlew build          # Build all (includes spotlessApply)
./gradlew spotlessApply  # Format code
./gradlew checkFormat    # Check formatting without applying
./gradlew test           # Run tests
```

## Adding New Features

### New Domain Model
1. Create model class extending `Model<M, Id<M>, STATE>` with `@AutoBuilder`
2. Create state enum
3. Create domain events extending `ModelEvent<M>`
4. Create Flyway migration SQL
5. Generate JOOQ classes (via Docker plugin)
6. Create repository extending `ModelRepository`
7. Register in `RepositoryRegistry`

### New Entity
1. Create entity class extending `Entity<E, ID, STATE>` with `@AutoBuilder`
2. Create Flyway migration
3. Generate JOOQ classes
4. Create repository extending `EntityRepository`
5. Register in `RepositoryRegistry`

### New Action
1. Create action class extending `Action<PARAM, RESULT>`
2. Implement `perform(principal, params)` using `plan.add()` / `plan.update()`
3. Register in `ActionRegistry`

### New Database Support
1. Add test subproject under `ekbatan-integration-tests/core-repo`
2. Create database-specific Flyway migrations
3. Add `DummyRepository` implementation with dialect-specific converters
4. Create test runner extending `BaseRepositoryTest`
5. Handle dialect differences in `AbstractRepository` if needed
