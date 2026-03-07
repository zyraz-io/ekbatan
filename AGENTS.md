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
│   │   ├── domain/                        # Domain abstractions (Model, Entity, ModelEvent, Id, MicroType)
│   │   ├── repository/                    # Repository pattern (AbstractRepository, ModelRepository, EntityRepository)
│   │   ├── action/                        # Action/Command pattern (Action, ActionExecutor, ActionPlan)
│   │   │   └── persister/                 # Change & event persistence
│   │   │       └── event/dual_table/      # Dual-table event store implementation
│   │   ├── persistence/                   # Transaction management, JOOQ converters
│   │   └── config/                        # DataSourceConfig
│   └── ekbatan-core-repo-test/            # Shared test infrastructure for repositories
│       ├── src/main/java/.../test/model/  # Dummy test model & events
│       ├── src/main/java/.../test/repository/  # BaseRepositoryTest (abstract)
│       ├── ekbatan-core-repo-test-pg/     # PostgreSQL test runner
│       ├── ekbatan-core-repo-test-mysql/  # MySQL test runner
│       └── ekbatan-core-repo-test-mariadb/# MariaDB test runner
├── ekbatan-annotation-processor/          # @AutoBuilder annotation processor (JavaPoet)
├── ekbatan-examples/                      # Example wallet application
│   ├── src/main/java/io/ekbatan/examples/wallet/
│   │   ├── models/                        # Wallet (Model), Product (Entity), events
│   │   ├── repository/                    # WalletRepository, ProductRepository
│   │   └── action/                        # WalletCreateAction, WalletDepositMoneyAction
│   └── src/main/resources/db/migration/   # Flyway SQL migrations (PostgreSQL)
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
- **`MicroType<T>`** — Base value object wrapper for type safety.
- **`Persistable`** — Interface adding `version` and `nextVersion()` for optimistic locking.
- **`GenericState`** — Default state enum (ACTIVE, DELETED) for entities without custom states.

Key pattern: Domain objects are **immutable**. Mutations create new instances. Models accumulate events during mutations (e.g., `wallet.deposit(amount)` returns a new Wallet with a `WalletMoneyDepositedEvent` attached).

### Repository Layer (`ekbatan-core/...core/repository/`)

JOOQ-based persistence:

- **`AbstractRepository`** — Core implementation with full CRUD, optimistic locking (version field), soft deletion (filters DELETED state), and dialect-aware SQL for MySQL vs MariaDB vs PostgreSQL.
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
- **`DualTableEventPersister`** — Default implementation using `action_events` + `model_events` tables.

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

### Optimistic Locking
Every persistable has a `version` field. On update, the repository checks that the version matches. If not, `StaleRecordException` is thrown. `nextVersion()` increments the version.

### Soft Deletion
Records are never physically deleted. The `state` field is set to `DELETED`. Queries automatically filter out deleted records.

### Event Sourcing (Dual-Table)
Models accumulate `ModelEvent` instances. When persisted via `ChangePersister`, events are extracted and stored in two tables:
- `action_events` — One row per action execution (action name, params, timestamps)
- `model_events` — One row per domain event (model ID, event type, JSON payload, linked to action)

### Multi-Database Support
The framework supports PostgreSQL, MySQL, and MariaDB. Dialect differences are handled in:
- `AbstractRepository` — Different SQL strategies for upsert/update
- JOOQ converters — `JSONB` (PostgreSQL) vs `JSON` (MySQL/MariaDB)
- UUID handling — Native UUID (PostgreSQL) vs binary/string (MySQL)
- Test infrastructure — Separate test subprojects per database

### Builder Pattern
Two variants:
1. **Manual builders** — `Model.Builder` and `Entity.Builder` base classes with fluent API
2. **Generated builders** — `@AutoBuilder` annotation generates builders at compile time

### Naming Conventions
- Java: `camelCase` fields, `PascalCase` classes
- SQL: `snake_case` columns and tables
- Packages: `lowercase` with underscores for multi-word directories (e.g., `dual_table`)
- Commit messages: `EKB-XXXX` ticket prefix

## Testing

### Test Infrastructure
- **`BaseRepositoryTest`** — Large abstract test class in `ekbatan-core-repo-test` covering all CRUD operations, optimistic locking, soft deletion, pagination
- **`Dummy` model** — Test-only model mirroring the Wallet structure
- **Database-specific runners** — Separate subprojects for PostgreSQL, MySQL, MariaDB using TestContainers
- **Flyway migrations** — Per-database test migrations in each test subproject

### Test Stack
- JUnit 5 (Jupiter)
- MockK (mocking)
- AssertJ (fluent assertions)
- TestContainers (database containers)

### Running Tests
```bash
./gradlew test                                    # All tests
./gradlew :ekbatan-core:ekbatan-core-repo-test:ekbatan-core-repo-test-pg:test    # PostgreSQL only
./gradlew :ekbatan-core:ekbatan-core-repo-test:ekbatan-core-repo-test-mysql:test # MySQL only
./gradlew :ekbatan-core:ekbatan-core-repo-test:ekbatan-core-repo-test-mariadb:test # MariaDB only
./gradlew :ekbatan-examples:test                  # Example tests
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
1. Add test subproject under `ekbatan-core-repo-test`
2. Create database-specific Flyway migrations
3. Add `DummyRepository` implementation with dialect-specific converters
4. Create test runner extending `BaseRepositoryTest`
5. Handle dialect differences in `AbstractRepository` if needed
