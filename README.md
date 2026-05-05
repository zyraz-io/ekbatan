# Ekbatan

**A Java persistence framework with the outbox pattern built in - easy to do and hassle-free.**

The **outbox pattern** stores domain events alongside state in the same atomic database transaction - both writes land together, or neither does - making the database the single source of truth for both. Downstream tools like Debezium can later publish those events to Kafka, Pulsar, or any other broker, without the dual-write trap.

Ekbatan is a Java library you embed in your application - Spring, Quarkus, Micronaut, or plain Java. It does **not** replace your full-stack framework; it replaces the persistence layer where Spring Data, Hibernate, JPA, or hand-rolled JDBC usually live.

Built for **Java 25+**, sitting directly on **JOOQ**, designed around **virtual threads**.

---

## The Big Picture

Every business change produces **two things at once** - a new state, and an event recording how it got there. They have to travel together:

```
                       ┌──▶  STATE:   balance = $0
  Sara opens wallet  ──┤
                       └──▶  EVENT:   WalletCreated

                       ┌──▶  STATE:   balance = $250
  Sara deposits $250 ──┤
                       └──▶  EVENT:   MoneyDeposited($250)

                       ┌──▶  STATE:   balance = $150
  Sara spends   $100 ──┤
                       └──▶  EVENT:   MoneySpent($100)
```

The **state** is what your application reads. The **events** are what downstream systems consume - audit logs, analytics, other services. Persisting them as **two separate writes** - state to the database, events to Kafka - is where things break:

```
  ✗  TWO WRITES - the dual-write problem        ✓  ONE WRITE + OUTBOX

             app                                              app
            ╱   ╲                                              │
           ▼     ▼                                             ▼
        ┌────┐ ┌───────┐                              ┌────────────────────┐
        │ DB │ │ Kafka │                              │      DATABASE      │
        └────┘ └───────┘                              │   state │  outbox  │
        state    events                               └────────────────────┘
                                                               │
        crash between the two writes                           ▼  CDC tails the outbox
          ⇒ DB and Kafka disagree                          ┌───────┐
            ⇒ downstream consumers                         │ Kafka │
              see an inconsistent view                     └───────┘
                                                                ⇒ events shipped later,
                                                                  always in sync with state
```

The **outbox pattern** - the right-hand side - is well known and not specific to Ekbatan. What Ekbatan adds is making it **easy to do**: the outbox table, the row schema, and the write path are part of the framework. Applications just attach events to their domain objects, and the framework persists state-and-events together.

Visually, every action's commit looks like this - one transaction can touch as many domain tables as the action needs, plus the outbox:

```
┌──────────────────  ONE DATABASE TRANSACTION  ──────────────────────┐
│                                                                    │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────────┐    │
│  │    wallets     │  │     orders     │  │  eventlog.events   │    │
│  │   (UPDATE)     │  │    (INSERT)    │  │     (INSERT)       │    │
│  ├────────────────┤  ├────────────────┤  ├────────────────────┤    │
│  │ id             │  │ id             │  │ id                 │    │
│  │ balance        │  │ wallet_id      │  │ action_id          │    │
│  │ version        │  │ amount         │  │ event_type         │    │
│  │ ...            │  │ status: placed │  │ payload (JSONB)    │    │
│  └────────────────┘  │ ...            │  │ ...                │    │
│                      └────────────────┘  └────────────────────┘    │
│         domain                domain               outbox          │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
       commit (all rows persist)  -or-  rollback (nothing persists)
```

Placing an order, for example, might insert a new `orders` row, debit the `wallets` row, and insert one `eventlog.events` row per emitted event - all in the same atomic write. Whatever the action stages on its `ActionPlan`, the executor flushes together.

Around this core, the framework provides multi-database support, optional horizontal sharding, configurable retry policies, and built-in observability. Each capability is opt-in and adds no overhead when unused.

---

## Core Concepts

Five types do most of the work.

### Action
A unit of business work: deposit money, create order, ship parcel. An Action does not write to the database; it stages changes onto its `ActionPlan`.

### ActionPlan
A staging area held by every Action. Code calls `plan.add(...)` or `plan.update(...)` to register new or modified domain objects. Nothing is committed until the executor runs.

### ActionExecutor
Takes the plan, opens one transaction, writes the state and the events, and commits or rolls back. Also handles retries, tracing, and shard routing.

### Model
A domain object whose changes produce events. Mutations return a new immutable instance with a `ModelEvent` attached. Examples: Wallet, Order, Subscription.

### Entity
Persistable, version-tracked, and immutable, but produces no events. Use it for lookup tables, settings, or auxiliary records whose history is not consumed downstream.

Supporting types - `ModelEvent`, `Repository`, `TransactionManager`, `DatabaseRegistry`, `ShardedUUID` - are introduced in the relevant sections below.

Putting these five together, every action follows the same two-phase lifecycle:

```
        executor.execute(WalletDepositAction.class, params)
                              │
                              ▼
   ┌─── Phase 1 - Action.perform()  (no DB transaction yet) ────┐
   │                                                            │
   │   1. Read from repositories   (primary or readonly DB)     │
   │   2. Build new immutable Models / Entities                 │
   │   3. Attach Events to the Models                           │
   │   4. Stage them on the ActionPlan:                         │
   │         plan.add(newOrder)                                 │
   │         plan.update(updatedWallet)                         │
   │   5. Return a result value                                 │
   │                                                            │
   │   No database writes in this phase.                        │
   └────────────────────────────────────────────────────────────┘
                              │
                              ▼
   ┌─── Phase 2 - Executor.persistChanges()  (one atomic TX) ───┐
   │                                                            │
   │   1. Group plan changes by ShardIdentifier                 │
   │   2. TransactionManager.inTransaction(shard, () -> {       │
   │        Repository.addAll / updateAll  →  domain rows       │
   │        EventPersister.persistActionEvents  →  outbox rows  │
   │        commit  ─or─  rollback                              │
   │      });                                                   │
   │   3. On StaleRecordException → retry whole action          │
   │                                                            │
   │   All writes land together, or none at all.                │
   └────────────────────────────────────────────────────────────┘
                              │
                              ▼
                  result returned to the caller
```

Phase 1 is pure construction - reads are allowed, but no writes happen. Phase 2 is the only place the framework opens a transaction, and it always wraps every staged change plus the matching event rows together. Anything that throws inside Phase 2 rolls the whole transaction back; on optimistic-lock conflicts (`StaleRecordException`) the executor re-runs the entire action from Phase 1 with a fresh plan.

---

## Learn by Example: A Wallet

A wallet is a **Model** with an owner, a currency, and a balance:

```java
@AutoBuilder
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

    public Wallet deposit(BigDecimal amount) {
        var newBalance = balance.add(amount);
        return copy()
                .withEvent(new WalletMoneyDepositedEvent(id, amount, newBalance))
                .balance(newBalance)
                .build();
    }
    // close(), copy()...
}
```

`deposit(...)` returns a new wallet with the corresponding event attached - no database call is made here.

An **Action** wraps the operation so the executor can run it:

```java
@EkbatanAction
public class WalletDepositAction extends Action<WalletDepositAction.Params, Wallet> {

    public record Params(Id<Wallet> walletId, BigDecimal amount) {}

    private final WalletRepository walletRepository;

    public WalletDepositAction(Clock clock, WalletRepository walletRepository) {
        super(clock);
        this.walletRepository = walletRepository;
    }

    @Override
    protected Wallet perform(Principal principal, Params params) {
        var wallet = walletRepository.getById(params.walletId().getValue());
        var updated = wallet.deposit(params.amount());
        return plan().update(updated);
    }
}
```

No database write yet - only a registered change on the **plan**.

The **Action Executor** runs the action by class and parameters:

```java
Wallet result = executor.execute(
        () -> "alice",
        WalletDepositAction.class,
        new WalletDepositAction.Params(walletId, new BigDecimal("25.50")));
```

The executor opens a single transaction, writes the new wallet row, writes the `WalletMoneyDepositedEvent` row into the events table, and commits. If any step throws, both writes are rolled back together.

The `WalletRepository` injected into the action above maps the domain to JOOQ records. The base class gives you full CRUD; you add custom queries - `readonlyDb()` for replica reads, `db()` for primary reads:

```java
@EkbatanRepository
public class WalletRepository extends ModelRepository<Wallet, WalletsRecord, Wallets, UUID> {

    public WalletRepository(DatabaseRegistry databaseRegistry) {
        super(Wallet.class, WALLETS, WALLETS.ID, databaseRegistry);
    }

    // Replica read - list / search queries that tolerate replication lag
    public List<Wallet> findAllByOwnerId(UUID ownerId) {
        return readonlyDb()
                .selectFrom(WALLETS)
                .where(WALLETS.OWNER_ID.eq(ownerId))
                .fetch(this::fromRecord);
    }

    // Primary read - strongly-consistent reads (e.g. immediately after a write)
    public Optional<Wallet> findByIdOnPrimary(UUID walletId) {
        return db()
                .selectFrom(WALLETS)
                .where(WALLETS.ID.eq(walletId))
                .fetchOptional(this::fromRecord);
    }

    @Override
    public Wallet fromRecord(WalletsRecord r) { /* … */ }

    @Override
    public WalletsRecord toRecord(Wallet w) { /* … */ }
}
```

A **Distributed Job** is periodic background work that should run on **at most one** instance across the cluster - daily reports, hourly cleanups, periodic reconciliations:

```java
@EkbatanDistributedJob
public class DailyWalletReportJob extends DistributedJob {

    private final ReportService reportService;

    public DailyWalletReportJob(ReportService reportService) {
        this.reportService = reportService;
    }

    @Override public String name()       { return "daily-wallet-report"; }   // cluster-wide unique
    @Override public Schedule schedule() { return Schedules.daily(LocalTime.of(2, 0)); }

    @Override
    public void execute(ExecutionContext ctx) {
        reportService.generateAndSend();
    }
}
```

An **Event Handler** reacts to a specific event in the same JVM after the action commits - for sending notifications, writing audit rows, or triggering downstream workflows. The framework's fan-out and dispatch jobs deliver each committed event with retry and at-least-once semantics:

```java
@EkbatanEventHandler
public class WalletMoneyDepositedEventHandler implements EventHandler<WalletMoneyDepositedEvent> {

    private final NotificationService notificationService;

    public WalletMoneyDepositedEventHandler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override public String name()                                 { return "wallet-deposit-notification"; }
    @Override public Class<WalletMoneyDepositedEvent> eventType()  { return WalletMoneyDepositedEvent.class; }

    @Override
    public void handle(EventEnvelope<WalletMoneyDepositedEvent> envelope) {
        notificationService.notifyDeposit(envelope.event.modelId, envelope.event.amount);
    }
}
```

---

## Install

> ⚠️ **Coming soon** - Ekbatan is being prepared for publication on Maven Central. Once published, this section will list the Gradle and Maven coordinates for each module.
>
> In the meantime, you can clone this repository and run `./gradlew publishToMavenLocal` to consume the artifacts from your local Maven cache.

---

## Capabilities

Each topic links to a focused deep-dive doc with the full surface area, schema, and examples.

### Get started
- [Wiring without DI](docs/wiring/without-di.md) - full plain-Java end-to-end snippet, every line explained
- [Wiring with Spring Boot](docs/wiring/spring.md) - one starter dep + `@Ekbatan*` annotations
- [Wiring with Quarkus](docs/wiring/quarkus.md) - extension + build-step / native specifics
- [Wiring with Micronaut](docs/wiring/micronaut.md) - integration jar + compile-time visitor

### Core
- [The outbox: atomic state + events](docs/concepts/outbox.md) - the framework's atomic state-and-events guarantee
- [Actions, ActionPlan, ActionExecutor](docs/concepts/actions.md) - the two-phase lifecycle, retries, no nesting, single-threaded perform
- [Models and Entities](docs/concepts/models-and-entities.md) - when to use which, immutability, `@AutoBuilder`, optimistic locking

### Database
- [Repositories on JOOQ](docs/database/repositories.md) - `db()` / `readonlyDb()` / `txDb()` / `txDbElseDb()`, soft delete, custom queries
- [TransactionManager](docs/database/transaction-manager.md) - direct transactional DB access outside the Action pipeline
- [Outbox schema](docs/database/outbox-schema.md) - the SQL DDL of `eventlog.events`, the `delivered` flag, `event_notifications`, indexes
- [Sharding](docs/database/sharding.md) - group + member, `ShardedUUID`, custom `ShardingStrategy`, cross-shard rules
- [Pessimistic locking via `KeyedLockProvider`](docs/database/keyed-locks.md) - five backends (Postgres, MySQL, MariaDB, Redis, in-process), reentrancy contract
- [Multi-database (PostgreSQL / MySQL / MariaDB)](docs/database/multi-database.md) - dialect cheatsheet, init scripts, partial indexes
- [JOOQ codegen](docs/database/jooq-codegen.md) - per-dialect `build.gradle.kts` blocks for `dev.monosoul.jooq-docker`

### Jobs
- [Distributed background jobs](docs/jobs/distributed-jobs.md) - `@EkbatanDistributedJob`, db-scheduler-backed cluster exclusivity

### Events out
- [Listen-to-yourself: in-process event handlers](docs/events/local-event-handler.md) - `@EkbatanEventHandler`, fan-out + dispatch jobs, retry & expiry, idempotency
- [Streaming via Debezium → Kafka](docs/events/event-streaming.md) - JSON / Avro / Protobuf SMTs, the router, topic naming

### Observability & native
- [OpenTelemetry tracing](docs/runtime/observability.md) - span hierarchy, attributes, retry events
- [GraalVM native-image](docs/runtime/native-image.md) - auto-loading Features, scan-package overrides, framework-specific notes

---

## Where Ekbatan fits

Ekbatan is **not** a replacement for Spring, Quarkus, or Micronaut. HTTP, dependency injection, configuration, security - those concerns remain with the host framework.

Ekbatan **is** a replacement for the persistence layer typically built with **Spring Data, Hibernate, JPA, MyBatis, or hand-rolled JDBC + transaction management**. It is intended for applications that need:

- writes to a relational database with strong transactional guarantees,
- a reliable audit trail of business changes,
- propagation of changes to downstream consumers - via Kafka (Debezium SMT pipeline) or in-process (fan-out + handling jobs) - without dual-write coordination.

---

## Non-goals

- **Nested or composable actions.** The action boundary *is* the transaction boundary; cross-action orchestration belongs above the framework.
- **Saga orchestration.** Cross-service workflows are the responsibility of the layer above this framework.
- **Reactive runtime.** Concurrency is handled by Java 25 virtual threads.
- **Bridging to Spring's `@Transactional` / `PlatformTransactionManager`.** Ekbatan owns its own `TransactionManager`. Code outside an Action that needs database transactions should use the host framework's facilities directly on its own datasource.

---

## Stack & requirements

- **Java 25** - required (uses `ScopedValue`, records, recent language features). The Kafka Connect SMT plugins target Java 21 to match the Connect runtime; everything else targets 25.
- **JOOQ 3.20** - type-safe SQL.
- **HikariCP 7** - connection pooling.
- **PostgreSQL, MySQL, or MariaDB** - dialect differences handled internally.
- *(Optional)* **OpenTelemetry SDK** - for tracing.
- *(Optional)* **Debezium + Kafka Connect** - for event streaming.
- *(Optional)* **Redis (Redisson)** - for the distributed `KeyedLockProvider`.

---

## Examples

The [`ekbatan-integration-tests/`](./ekbatan-integration-tests) directory contains complete, runnable examples that mirror every snippet on this page. Each subproject is a working application you can study, run, and adapt:

| Subproject | What it demonstrates |
|---|---|
| `postgres-simple/` | Single-database wallet with create / deposit / close actions |
| `postgres-sharded/` | The same wallet model sharded across multiple Postgres instances, with cross-shard tests |
| `core-repo/{pg,mysql,mariadb}/` | Repository CRUD coverage across all three supported databases |
| `keyed-lock-provider/{pg,mariadb,mysql,redis}/` | `KeyedLockProvider` implementations and reentrancy/timeout coverage |
| `distributed-jobs-pg/` | `JobRegistry` + `DistributedJob` cluster-exclusive scheduling |
| `event-pipeline/` | End-to-end Debezium → Kafka pipeline with JSON, Avro (SMT), and Protobuf (SMT) variants |
| `local-event-handler/{shared,pg,mariadb,mysql}/` | In-process consumer (fan-out + handling jobs) on PG / MariaDB / MySQL |
| `di/{spring-boot-starter,quarkus,micronaut}/` | DI integration smoke tests showcasing the `@Ekbatan*` annotations |

---

## Building & testing

```bash
./gradlew build           # full build (includes spotlessApply)
./gradlew test            # all tests
./gradlew spotlessApply   # format
```

Tests use TestContainers and require Docker to be running.

---

## Documentation

- **[docs/](docs/README.md)** - capability deep-dives (linked from *Capabilities* above)
- **[AGENTS.md](AGENTS.md)** - full architecture, conventions, and contributor guide
