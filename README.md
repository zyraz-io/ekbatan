# Ekbatan

**A Java persistence framework with the outbox pattern built in — implicit and hassle-free.**

The **outbox pattern** stores domain events alongside state in the same atomic database transaction — both writes land together, or neither does — making the database the single source of truth for both. Downstream tools like Debezium can later publish those events to Kafka, Pulsar, or any other broker, without the dual-write trap.

Ekbatan is a Java library you embed in your application — Spring, Quarkus, Micronaut, or plain Java. It does **not** replace your full-stack framework; it replaces the persistence layer where Spring Data, Hibernate, JPA, or hand-rolled JDBC usually live.

Built for Java 25+, sitting directly on JOOQ, designed around virtual threads.

---

## The Big Picture

In most business applications, two things matter equally — **what is true now**, and **what happened along the way**. Every change produces both: a new state, and an event that records it. For example:

- Sara opens a wallet → state: balance is **\$0**, event: `WalletCreated`
- Sara deposits \$250 → state: balance is **\$250**, event: `MoneyDeposited(\$250)`
- Sara spends \$100 → state: balance is **\$150**, event: `MoneySpent(\$100)`

Persisting the state in a database while separately publishing the events to a broker like Kafka creates the **dual-write problem** — if the process fails between the two writes, the database and the broker disagree, and downstream consumers see an inconsistent view.

The established solution is the **outbox pattern** — write the events into a table in the same transaction as the state, and propagate them later to event streaming tools such as Kafka or Pulsar to power an event-driven architecture. The pattern is well-known and not specific to Ekbatan.

Ekbatan makes the outbox pattern easy and implicit. The outbox table, the row schema, and the write path are part of the framework; applications simply attach events to their domain objects and never deal with the outbox plumbing directly.

Visually, every action's commit looks like this — one transaction can touch as many domain tables as the action needs, plus the outbox:

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
       commit (all rows persist)  —or—  rollback (nothing persists)
```

Placing an order, for example, might insert a new `orders` row, debit the `wallets` row, and insert one `eventlog.events` row per emitted event — all in the same atomic write. Whatever the action stages on its `ActionPlan`, the executor flushes together.

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

Supporting types — `ModelEvent`, `Repository`, `TransactionManager`, `DatabaseRegistry`, `ShardedUUID` — are introduced in the relevant sections below.

Putting these five together, every action follows the same two-phase lifecycle:

```
        executor.execute(WalletDepositAction.class, params)
                              │
                              ▼
   ┌─── Phase 1 — Action.perform()  (no DB transaction yet) ────┐
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
   ┌─── Phase 2 — Executor.persistChanges()  (one atomic TX) ───┐
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

Phase 1 is pure construction — reads are allowed, but no writes happen. Phase 2 is the only place the framework opens a transaction, and it always wraps every staged change plus the matching event rows together. Anything that throws inside Phase 2 rolls the whole transaction back; on optimistic-lock conflicts (`StaleRecordException`) the executor re-runs the entire action from Phase 1 with a fresh plan.

---

## Example: A Wallet

A wallet is a Model with an owner, a currency, and a balance:

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
    // deposit(), close(), copy()...
}
```

A deposit returns a new wallet with the corresponding event attached. No database call is made here:

```java
public Wallet deposit(BigDecimal amount) {
    var newBalance = balance.add(amount);
    return copy()
            .withEvent(new WalletMoneyDepositedEvent(id, amount, newBalance))
            .balance(newBalance)
            .build();
}
```

An Action wraps the operation so the executor can run it:

```java
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
        return plan.update(updated);
    }
}
```

No database write yet — only a registered change on the plan.

The executor runs the action by class and parameters:

```java
Wallet result = executor.execute(
        () -> "alice",
        WalletDepositAction.class,
        new WalletDepositAction.Params(walletId, new BigDecimal("25.50")));
```

The executor opens a single transaction, writes the new wallet row, writes the `WalletMoneyDepositedEvent` row into the events table, and commits. If any step throws, both writes are rolled back together.

---

## Going Deeper

### Models vs Entities

Both are immutable. Both are version-tracked. Both end up in tables with a `state` column that supports soft delete. The difference is whether mutations to them produce events.

|                                                          | Model | Entity |
|----------------------------------------------------------|:-----:|:------:|
| `id`, `state`, `version`                                 |  yes  |  yes   |
| `created_date` / `updated_date` managed by the framework |  yes  |   no   |
| Mutations emit `ModelEvent`s                             |  yes  |   no   |

Choose **Model** when a mutation should be recorded as an event — for downstream consumers, *listen-to-yourself* patterns within the same service, audit and compliance trails, or any other reason a discrete record of *what happened* is valuable. Choose **Entity** when the current state alone is sufficient and the history of individual mutations is not needed.

Entity does not ship framework-managed `created_date` / `updated_date` columns, but a subclass is free to add its own timestamp columns (or any other columns) to the underlying table — the framework simply will not populate or track them automatically.

### Optimistic Locking, Always

Every persistable carries a `version`. Updates always include `WHERE version = ?`:

```sql
UPDATE wallets
SET balance = ?, version = ?
WHERE id = ? AND version = ?
```

If another transaction got there first, zero rows are affected and Ekbatan throws `StaleRecordException`. The transaction unwinds, and (by default) the executor retries the action once with a 100ms delay. You can tune the policy — or remove it — per executor:

```java
ExecutionConfiguration.builder()
        .withRetry(StaleRecordException.class, new RetryConfig(3, Duration.ofMillis(50)))
        .build();
```

Ekbatan's own write path takes no pessimistic row locks — concurrent conflicts surface as `StaleRecordException` rather than blocked threads. When pessimistic locking is genuinely required, the next section introduces `KeyedLockProvider` — a session-scoped, key-based mutex that fits the Action lifecycle. For lower-level needs, applications can also issue `SELECT ... FOR UPDATE` directly against the `DSLContext`, either standalone or inside a transactional scope via `transactionManager.inTransaction(...)`.

### Pessimistic Locking with `KeyedLockProvider`

Some operations don't fit optimistic locking — **at-most-once idempotency** around external side effects (a webhook handler that calls a payment API), or **single-flight execution** across nodes (a daily reconciliation job). Others (a wallet deposit) can be done either way, but pessimistic locking avoids the retry thrash optimistic locking causes on hot accounts. For all of these, Ekbatan ships `KeyedLockProvider`. You call `provider.acquire(key, maxHold)` to get a `Lease`; closing the lease releases the lock. The key can be any `Object` — a `String`, a `UUID`, an `Id<Wallet>`. Two acquirers using the same key are mutually exclusive (the second waits until the first releases); acquirers using different keys don't block each other.

Four implementations: `InProcessKeyedLockProvider` (single JVM, semaphore-backed) and `PostgresKeyedLockProvider` / `MariaDBKeyedLockProvider` / `MySQLKeyedLockProvider` (cross-JVM, session-scoped at the database — auto-released if the holder crashes).

A wallet deposit, serialized per-wallet by acquiring the lease before reading and persisting:

```java
public class WalletDepositAction extends Action<WalletDepositAction.Params, Wallet> {

    private final WalletRepository walletRepo;
    private final KeyedLockProvider lockProvider;

    public record Params(Id<Wallet> walletId, BigDecimal amount) {}

    public WalletDepositAction(Clock clock, WalletRepository walletRepo, KeyedLockProvider lockProvider) {
        super(clock);
        this.walletRepo = walletRepo;
        this.lockProvider = lockProvider;
    }

    @Override
    protected Wallet perform(Principal principal, Params params) throws InterruptedException {
        try (var lockLease = lockProvider.acquire(params.walletId, Duration.ofSeconds(10))) {
            var wallet = walletRepo.getById(params.walletId.getValue());
            return plan.update(wallet.copy()
                    .balance(wallet.balance.add(params.amount))
                    .build());
        }
    }
}
```

The same deposit can be implemented with optimistic locking alone — but each concurrent conflict on the same wallet would surface as a `StaleRecordException` that triggers a retry. With pessimistic locking, callers wait briefly for the lease and then succeed on their first attempt; the retry loop disappears, trading retry-driven tail latency for a small, predictable wait. On hot wallets that's usually the better trade.

Two things worth noting:

- **`maxHold` (10s above) is a safety net, not a hint.** If the action overruns, the lock auto-releases — capping the blast radius of a hung holder regardless of what the calling thread is doing.
- **Each `acquire` borrows its own JDBC connection** for the lifetime of the lease. For lock-heavy workloads, point the provider at a dedicated pool via `member.configFor("lockConfig")` so locks don't starve normal queries.

A typical `ShardingConfig` member with a dedicated lock pool:

```yaml
sharding:
  defaultShard: { group: 0, member: 0 }
  groups:
    - group: 0
      name: global
      members:
        - member: 0
          name: global-eu-1
          configs:
            primaryConfig:
              jdbcUrl: jdbc:postgresql://primary-eu-1:5432/db
              username: app
              password: secret
              maximumPoolSize: 20
              minimumIdle: 5

            secondaryConfig:
              jdbcUrl: jdbc:postgresql://replica-eu-1:5432/db
              username: app
              password: secret
              maximumPoolSize: 10

            # Dedicated pool for KeyedLockProvider — keeps lock acquisitions
            # from competing for connections with normal queries.
            lockConfig:
              jdbcUrl: jdbc:postgresql://primary-eu-1:5432/db   # same DB; locks must coordinate on the same instance
              username: app
              password: secret
              maximumPoolSize: 40         # per-instance — sized for the concurrent leases this instance will hold
              minimumIdle: 5
              leakDetectionThreshold: 120000   # set comfortably above your largest expected maxHold; catches real leaks without warning on legitimate long-held leases
```

Wiring up a `PostgresKeyedLockProvider` from the parsed `ShardingConfig`:

```java
import static io.ekbatan.core.concurrent.PostgresKeyedLockProvider.Builder.postgresKeyedLockProvider;
import static io.ekbatan.core.persistence.ConnectionProvider.hikariConnectionProvider;

// Pull the lockConfig entry from whichever member you want to coordinate on
var lockDataSourceConfig = shardingConfig.groups.get(0).members.get(0)
        .configFor("lockConfig")
        .orElseThrow();

var lockProvider = postgresKeyedLockProvider()
        .connectionProvider(hikariConnectionProvider(lockDataSourceConfig))
        .build();
```

The same pattern works for `MariaDBKeyedLockProvider` and `MySQLKeyedLockProvider` — just swap the builder.

> **Caveat: not the right primitive at very high concurrency.** `KeyedLockProvider` fits coarse-grained coordination (single-flight cron jobs, admin actions, per-shard locks) and low-to-medium contention on the write path. At higher concurrency, every held lease - and every thread blocked waiting for one - pins a JDBC connection for its entire lifetime, which can quickly demand more connections than your database is sized for.

### Soft Deletion

By default, records are never physically removed. Their `state` flips to `DELETED` and queries automatically filter them out, keeping the history of every row intact for replay or audit. When a physical delete is genuinely required — for example to honor an erasure request or purge expired data — applications can issue the `DELETE` directly through the JOOQ repository.

### Repositories

Each persistable type has a repository that knows how to convert it to and from a JOOQ record. Repositories extend either `ModelRepository` (for Models) or `EntityRepository` (for Entities), both of which build on `AbstractRepository`. The base class provides the full CRUD surface:

```java
public abstract class AbstractRepository<PERSISTABLE, RECORD, TABLE, DB_ID> {

    // Subclass contract
    public abstract PERSISTABLE fromRecord(RECORD record);
    public abstract RECORD toRecord(PERSISTABLE domainObject);

    // Writes
    public PERSISTABLE add(PERSISTABLE domainObject);
    public List<PERSISTABLE> addAll(Collection<PERSISTABLE> domainObjects);
    public PERSISTABLE update(PERSISTABLE domainObject);
    public List<PERSISTABLE> updateAll(Collection<PERSISTABLE> domainObjects);

    // Reads
    public Optional<PERSISTABLE> findById(DB_ID id);
    public PERSISTABLE getById(DB_ID id);                            // throws if missing
    public List<PERSISTABLE> findAllByIds(Collection<DB_ID> ids);
    public List<PERSISTABLE> findAll();
    public Optional<PERSISTABLE> findOneWhere(Condition condition);
    public List<PERSISTABLE> findAllWhere(Condition condition);

    // Counts and existence
    public long count();
    public long countWhere(Condition condition);
    public boolean existsById(DB_ID id);
    public boolean existsWhere(Condition condition);

    // Direct DSLContext access — for custom queries
    protected DSLContext db();                // primary, default shard
    protected DSLContext readonlyDb();        // secondary, default shard
    protected Optional<DSLContext> txDb();    // current transaction context, if any
    protected DSLContext txDbElseDb();        // transaction context, or fallback to db()
}
```

A concrete repository implements only `fromRecord` and `toRecord`; the rest is inherited. Optimistic locking, soft-delete filtering, dialect handling, and shard routing are all applied automatically by the base class.

```java
public class WalletRepository extends ModelRepository<Wallet, WalletsRecord, Wallets, UUID> {

    public WalletRepository(DatabaseRegistry databaseRegistry) {
        super(Wallet.class, WALLETS, WALLETS.ID, databaseRegistry);
    }

    // Custom query — example of how a domain-specific query method is added.
    public List<Wallet> findAllByOwnerId(UUID ownerId) {
        return readonlyDb()
                .selectFrom(WALLETS)
                .where(WALLETS.OWNER_ID.eq(ownerId))
                .fetch(this::fromRecord);
    }

    @Override
    public Wallet fromRecord(WalletsRecord record) {
        return wallet().id(Id.of(Wallet.class, record.getId()))
                .version(record.getVersion())
                .state(WalletState.valueOf(record.getState()))
                .ownerId(record.getOwnerId())
                .currency(Currency.getInstance(record.getCurrency()))
                .balance(record.getBalance())
                .createdDate(record.getCreatedDate())
                .updatedDate(record.getUpdatedDate())
                .build();
    }

    @Override
    public WalletsRecord toRecord(Wallet model) {
        return new WalletsRecord(
                model.id.getValue(),
                model.version,
                model.state.name(),
                model.ownerId,
                model.currency.getCurrencyCode(),
                model.balance,
                model.createdDate,
                model.updatedDate);
    }
}
```

Subclasses are free to add domain-specific query methods. The `findAllByOwnerId` example above uses `readonlyDb()` to construct a JOOQ query directly — note that when bypassing the inherited helpers, soft-delete filtering becomes the subclass's responsibility. For simpler predicate-based queries, the inherited helpers (`findAllWhere`, `findOneWhere`, `existsWhere`, `countWhere`) preserve soft-delete filtering and shard routing automatically. Either path stays within the same shard- and transaction-aware context as the rest of the framework.

### `@AutoBuilder`

Domain classes use the builder pattern. To avoid writing builders by hand, annotate the class:

```java
@AutoBuilder
public final class Wallet extends Model<Wallet, Id<Wallet>, WalletState> { ... }
```

A `WalletBuilder` is generated at compile time with fluent setters, getters, a `wallet()` static factory, and a `build()` method. `@AutoBuilder` is opt-in; if a domain class needs custom builder logic, write the builder by hand instead.

### What the Event Log Looks Like

Every event Ekbatan writes lands in a single denormalized table:

```
eventlog.events
─────────────────────────────────
id              UUID
namespace       text
action_id       UUID
action_name     text
action_params   JSONB
started_date    timestamptz
completion_date timestamptz
model_id        UUID
model_type      text
event_type      text
payload         JSONB
event_date      timestamptz
```

One row per event. Each row carries everything a downstream consumer needs — the action that caused it, when it ran, the params it received, and the event payload itself. Actions that produce zero events still get a sentinel row (no `event_type`), so the action's existence is always recorded.

Because every row already includes its action context, downstream consumers don't need to join back to anything. They tail the table and ship rows.

### Putting It Together

A minimal wiring of the executor looks like this:

```java
// Two datasource configs — primary handles writes and transactional reads,
// secondary handles non-transactional reads (typically a read replica).
var primaryConfig = dataSourceConfig()
        .jdbcUrl(primaryJdbcUrl).username(user).password(pass)
        .maximumPoolSize(10)
        .build();

var readonlyConfig = dataSourceConfig()
        .jdbcUrl(readReplicaJdbcUrl).username(user).password(pass)
        .maximumPoolSize(10)
        .build();

var tm = new TransactionManager(
        hikariConnectionProvider(primaryConfig),     // writes + transactional reads
        hikariConnectionProvider(readonlyConfig),    // non-transactional reads (read replica)
        SQLDialect.POSTGRES);

var databaseRegistry = databaseRegistry().withDatabase(tm).build();
var walletRepo = new WalletRepository(databaseRegistry);

var executor = actionExecutor()
        .namespace("billing")
        .databaseRegistry(databaseRegistry)
        .objectMapper(new ObjectMapper())
        .repositoryRegistry(repositoryRegistry()
                .withModelRepository(Wallet.class, walletRepo)
                .build())
        .actionRegistry(actionRegistry()
                .withAction(WalletCreateAction.class, () -> new WalletCreateAction(clock))
                .withAction(WalletDepositAction.class, () -> new WalletDepositAction(clock, walletRepo))
                .build())
        .build();
```

The two `hikariConnectionProvider` arguments are the **primary** (writes plus any reads inside an active transaction) and the **secondary** (non-transactional reads). Pointing the secondary at a read replica lets `findAll`, `findAllWhere`, `count`, and other non-transactional reads scale independently from the write path. If the deployment has no read replica yet, point both arguments at the same datasource — the framework treats them identically in that case.

In a Spring or Quarkus application, the executor is built once and registered as a bean. Controllers and services then call `executor.execute(...)` like any other dependency.

---

## Sharding

Sharding is opt-in and has no overhead when disabled. Single-database deployments can skip this section.

### Addressing

Ekbatan uses a two-level addressing scheme:

- **Group** — a business or regulatory boundary (for example, *"Mexico data stays in Mexico"*). 8 bits, up to 256 groups.
- **Member** — a performance boundary within a group. 6 bits, up to 64 members per group.

A `ShardIdentifier(group, member)` is a numeric address. Each shard is a regular `TransactionManager`, constructed with its own primary and secondary datasources, and registered in the `DatabaseRegistry`:

```java
private static final ShardIdentifier GLOBAL_SHARD = ShardIdentifier.of(0, 0);
private static final ShardIdentifier MEXICO_SHARD = ShardIdentifier.of(1, 0);

// Each shard gets its own primary (writes) and readonly (read replica) datasource.
var globalPrimary  = dataSourceConfig().jdbcUrl(globalPrimaryUrl).username(u).password(p).maximumPoolSize(10).build();
var globalReadonly = dataSourceConfig().jdbcUrl(globalReadReplicaUrl).username(u).password(p).maximumPoolSize(10).build();
var mexicoPrimary  = dataSourceConfig().jdbcUrl(mexicoPrimaryUrl).username(u).password(p).maximumPoolSize(10).build();
var mexicoReadonly = dataSourceConfig().jdbcUrl(mexicoReadReplicaUrl).username(u).password(p).maximumPoolSize(10).build();

var globalTm = new TransactionManager(
        hikariConnectionProvider(globalPrimary),     // writes + transactional reads
        hikariConnectionProvider(globalReadonly),    // non-transactional reads (read replica)
        SQLDialect.POSTGRES,
        GLOBAL_SHARD);

var mexicoTm = new TransactionManager(
        hikariConnectionProvider(mexicoPrimary),
        hikariConnectionProvider(mexicoReadonly),
        SQLDialect.POSTGRES,
        MEXICO_SHARD);

var databaseRegistry = databaseRegistry()
        .withDefaultDatabase(globalTm)   // fallback for shards not explicitly registered
        .withDatabase(mexicoTm)
        .build();
```

Each shard owns its own pair of datasources. The default shard receives traffic for any logical shard that is not explicitly registered — for example, a wallet routed to an Australia shard that has not yet been deployed will fall through to the default. As before, if a shard has no read replica, point both providers at the same datasource.

### Group vs Member: The Two Axes

The two levels in a `ShardIdentifier` exist for different reasons:

- **Group is the *policy axis*** — boundaries forced on you from the outside. Regulatory data residency (*"Mexico data stays in Mexico"*), tenant isolation contracts, business-domain separation, compliance scoping. Group cardinality is driven by external constraints; you don't choose how many.
- **Member is the *performance axis*** — horizontal scaling within a single policy boundary. You add members when one database can't handle the write throughput. Member cardinality is driven by capacity planning.

If you follow the design's intent — group for policy, member for performance — the natural shape is **members of a group sharing network locality and failure domain** (same region, same data center, same VPC). Cross-member queries within a group are scatter-gather across every member, so intra-group latency directly affects their performance. Naming conventions like `global-eu-1`, `global-eu-2`, `global-eu-3` reflect this — members of the EU group all live in EU infrastructure.

That said, **these are conventions, not enforced rules.** The framework can't tell network topology from JDBC URLs, and plenty of applications legitimately need other patterns — multi-region active-active members for read locality, a tenant tier that doesn't map onto geography, a non-geographic policy axis entirely, or a single global lock service that spans every group. Use the axes however fits your application; the framework doesn't object.

### Declarative Configuration

Rather than wiring `TransactionManager`s by hand, you can describe the entire shard topology as a `ShardingConfig` and hand it to `DatabaseRegistry.fromConfig(config)`. The same structure maps directly to YAML, which makes it easy to load topology from a configuration file:

```yaml
sharding:
  defaultShard:
    group: 0
    member: 0

  groups:
    - group: 0
      name: global
      members:
        - member: 0
          name: global-eu-1
          configs:
            primaryConfig:                # required
              jdbcUrl: jdbc:postgresql://global-eu-1-rw.example.com:5432/wallets
              username: wallets_app
              password: ${EU_1_PASSWORD}
              maximumPoolSize: 20
              leakDetectionThreshold: 30000
            secondaryConfig:              # optional, but encouraged
              jdbcUrl: jdbc:postgresql://global-eu-1-ro.example.com:5432/wallets
              username: wallets_app_ro
              password: ${EU_1_RO_PASSWORD}
              maximumPoolSize: 20
            lockConfig:                   # user-defined; consumed by your own code
              jdbcUrl: jdbc:postgresql://global-eu-1-rw.example.com:5432/wallets
              username: wallets_lock
              password: ${EU_1_LOCK_PASSWORD}
              maximumPoolSize: 50
              leakDetectionThreshold: 0   # locks may sit idle while held; disable

        - member: 1
          name: global-eu-2
          configs:
            primaryConfig:
              jdbcUrl: jdbc:postgresql://global-eu-2-rw.example.com:5432/wallets
              username: wallets_app
              password: ${EU_2_PASSWORD}
              maximumPoolSize: 20
            secondaryConfig:
              jdbcUrl: jdbc:postgresql://global-eu-2-ro.example.com:5432/wallets
              username: wallets_app_ro
              password: ${EU_2_RO_PASSWORD}
              maximumPoolSize: 20

        - member: 2
          name: global-eu-3
          configs:
            primaryConfig:                # only primary — reads will use it as fallback for the secondary
              jdbcUrl: jdbc:postgresql://global-eu-3.example.com:5432/wallets
              username: wallets_app
              password: ${EU_3_PASSWORD}
              maximumPoolSize: 20

    - group: 1
      name: mexico
      members:
        - member: 0
          name: mexico-cdmx-1
          configs:
            primaryConfig:
              jdbcUrl: jdbc:postgresql://mexico-cdmx-1-rw.example.com:5432/wallets
              username: wallets_app
              password: ${MX_1_PASSWORD}
              maximumPoolSize: 20
            secondaryConfig:
              jdbcUrl: jdbc:postgresql://mexico-cdmx-1-ro.example.com:5432/wallets
              username: wallets_app_ro
              password: ${MX_1_RO_PASSWORD}
              maximumPoolSize: 20

        - member: 1
          name: mexico-cdmx-2
          configs:
            primaryConfig:
              jdbcUrl: jdbc:postgresql://mexico-cdmx-2-rw.example.com:5432/wallets
              username: wallets_app
              password: ${MX_2_PASSWORD}
              maximumPoolSize: 20
            secondaryConfig:
              jdbcUrl: jdbc:postgresql://mexico-cdmx-2-ro.example.com:5432/wallets
              username: wallets_app_ro
              password: ${MX_2_RO_PASSWORD}
              maximumPoolSize: 20
            analyticsConfig:              # user-defined; e.g. for reporting on a slow replica
              jdbcUrl: jdbc:postgresql://mexico-analytics.example.com:5432/wallets
              username: wallets_analytics
              password: ${MX_ANALYTICS_PASSWORD}
              maximumPoolSize: 5
```

**On the `configs:` map of each member:**

- **`primaryConfig` is required.** Every member must have one. The framework validates this at startup and refuses to build a `ShardMemberConfig` without it.
- **`secondaryConfig` is optional but encouraged.** Pointing it at a read replica lets `findAll`, `findAllWhere`, `count`, and other non-transactional reads scale independently from the write path. If absent, the framework transparently falls back to `primaryConfig` for those reads — the application code doesn't change.
- **Any other named entry is user-defined.** `lockConfig`, `analyticsConfig`, `auditConfig`, anything else: the framework doesn't consume them. Your application code reaches for them via `member.configFor("lockConfig")`, typically to wire its own components — for example, supplying a `ConnectionProvider` to `PostgresKeyedLockProvider`, or pointing a reporting tool at a slow replica.

This keeps every database connection that *belongs to a member* in one place. A reader of the config sees the full database surface for `mexico-cdmx-2` at a glance; nothing is scattered across separate config trees.

Accessing the configs from Java mirrors the YAML structure exactly:

```java
ShardMemberConfig member = ...;
DataSourceConfig primary = member.primaryConfig();                          // required, non-null
Optional<DataSourceConfig> secondary = member.secondaryConfig();            // empty if absent
Optional<DataSourceConfig> lock = member.configFor("lockConfig");           // user-defined
```

### Sharded Models

A Model that participates in sharding declares its ID type as `ShardedId<T>` instead of `Id<T>`:

```java
@AutoBuilder
public final class Wallet extends Model<Wallet, ShardedId<Wallet>, WalletState> {
    public final UUID ownerId;
    public final Currency currency;
    public final BigDecimal balance;
    // constructor, deposit(), copy()...

    public static WalletBuilder createWallet(
            ShardIdentifier shard, UUID ownerId, Currency currency, BigDecimal balance, Instant createdDate) {
        final var id = ShardedId.generate(Wallet.class, shard);   // shard bits encoded into the UUID
        return WalletBuilder.wallet()
                .id(id)
                .state(OPENED)
                .ownerId(ownerId)
                .currency(currency)
                .balance(balance)
                .createdDate(createdDate)
                .withInitialVersion()
                .withEvent(new WalletCreatedEvent(id, ownerId, currency, balance));
    }
}
```

`ShardedId` wraps a UUID v7 with the shard's group/member bits embedded inside `rand_b`. The shard can be recovered from the ID at any time without a lookup table:

```java
ShardedId<Wallet> id = ShardedId.generate(Wallet.class, MEXICO_SHARD);
ShardIdentifier shard = id.resolveShardIdentifier();   // group=1, member=0
```

The full routing flow, end to end:

```
ShardedUUID
┌──────────────────────────────────────────────────────────────┐
│ MSB: [48-bit timestamp][4-bit version=7][12-bit rand_a]      │
│ LSB: [2-bit variant][8-bit GROUP][6-bit MEMBER][48-bit rand] │
└──────────────────────────────────────────────────────────────┘
                              │
                              │  resolveShardIdentifier()
                              ▼
                ShardIdentifier(group=1, member=0)
                              │
                              │  DatabaseRegistry lookup
                              ▼
              ┌──────────────────────────────┐
              │ TransactionManager(mexico_db)│
              └──────────────────────────────┘
                              │
                              ▼
              DSLContext — ready to query
              (no lookup table, no routing service)
```

### Sharded Repositories

A repository opts into sharding by passing a `ShardingStrategy` to `super(...)`. The bundled `EmbeddedBitsShardingStrategy` decodes the shard from the UUID's embedded bits:

```java
public class WalletRepository extends ModelRepository<Wallet, WalletsRecord, Wallets, UUID> {

    public WalletRepository(DatabaseRegistry databaseRegistry) {
        super(Wallet.class, WALLETS, WALLETS.ID, databaseRegistry, new EmbeddedBitsShardingStrategy());
    }

    @Override
    public Wallet fromRecord(WalletsRecord record) {
        return wallet()
                .id(ShardedId.of(Wallet.class, ShardedUUID.from(record.getId())))
                // ... remaining fields ...
                .build();
    }

    // toRecord(...) unchanged
}
```

With the strategy in place, all CRUD methods route automatically:

```java
walletRepository.findById(walletId);      // routes to the wallet's shard (decoded from the ID)
walletRepository.update(updatedWallet);   // routes to the wallet's shard
walletRepository.findAllByIds(ids);       // groups IDs by shard, queries each shard once
walletRepository.findAll();               // scatter-gathers across all shards
```

Custom queries that drop into raw JOOQ can target a specific shard using the ID-aware accessors:

```java
public List<Wallet> findAllByOwnerOnSameShardAs(ShardedId<Wallet> walletId, UUID ownerId) {
    return readonlyDb(walletId.getValue())            // routes to the wallet's shard
            .selectFrom(WALLETS)
            .where(WALLETS.OWNER_ID.eq(ownerId))
            .fetch(this::fromRecord);
}
```

The same accessors come in `db(id)`, `txDb(id)`, and `txDbElseDb(id)` flavors, plus `db(persistable)` / `txDb(persistable)` overloads when the full domain object is on hand instead of the raw ID. To run a query against *every* shard, `dbs()` and `readonlyDbs()` return the full collection of `DSLContext`s.

### Custom Sharding Strategies

`EmbeddedBitsShardingStrategy` is the default strategy bundled with the framework, and most applications can use it as-is. IDs self-describe their shard, no lookup is needed, and routing requires no extra state — for the common case, this is the right pick.

A custom `ShardingStrategy` is only needed when the bundled default does not match the domain model. For example:

- **Column-based** — the shard is derived from a non-ID column on the entity (country code, tenant ID, region).
- **Hash-based** — hash one or more columns and modulo by the member count.
- **Range-based** — partition by ID ranges (e.g., wallets `0..1M` on shard A, `1M..2M` on shard B).
- **Lookup-table** — read the shard mapping from a separate table or external config service.

The interface has three methods:

```java
public interface ShardingStrategy<DB_ID> {
    boolean usesShardAwareId();
    Optional<ShardIdentifier> resolveShardIdentifierById(DB_ID id);
    Optional<ShardIdentifier> resolveShardIdentifier(Persistable<?> persistable);
}
```

A column-based example that routes by a country code on the entity:

```java
public final class CountryCodeShardingStrategy implements ShardingStrategy<UUID> {

    @Override
    public boolean usesShardAwareId() {
        return false;   // raw UUIDs do not encode the shard
    }

    @Override
    public Optional<ShardIdentifier> resolveShardIdentifierById(UUID id) {
        return Optional.empty();   // the ID alone is not enough
    }

    @Override
    public Optional<ShardIdentifier> resolveShardIdentifier(Persistable<?> p) {
        if (p instanceof CountryAware ca) {
            return Optional.of(switch (ca.countryCode()) {
                case "MX" -> ShardIdentifier.of(1, 0);
                case "AU" -> ShardIdentifier.of(2, 0);
                default   -> ShardIdentifier.of(0, 0);
            });
        }
        return Optional.empty();
    }
}
```

Wire it into the repository the same way as the bundled strategy — just pass it to `super(...)`:

```java
public WalletRepository(DatabaseRegistry databaseRegistry) {
    super(Wallet.class, WALLETS, WALLETS.ID, databaseRegistry, new CountryCodeShardingStrategy());
}
```

When `usesShardAwareId()` returns `false`, ID-only methods like `findById(id)` are rejected — without inspecting the entity, the framework cannot know which shard to query. In that case, use condition-based reads (`findAllWhere`, `findOneWhere`) which scatter-gather across all shards, or work from the persistable directly via `db(persistable)` / `txDb(persistable)` in custom queries.

### Picking the Shard When Creating

Sharded Models need a shard chosen *at creation time* — typically in the Action that creates them, derived from the business input:

```java
public class WalletCreateAction extends Action<WalletCreateAction.Params, Wallet> {

    public record Params(String countryCode) {}

    @Override
    protected Wallet perform(Principal principal, Params params) {
        var shardIdentifier = switch (params.countryCode()) {
            case "MX" -> MEXICO_SHARD;
            case "AU" -> AUSTRALIA_SHARD;
            default   -> GLOBAL_SHARD;
        };
        var wallet = createWallet(shardIdentifier, ownerId, EUR, BigDecimal.TEN, clock.instant()).build();
        return plan.add(wallet);
    }
}
```

After this point, the shard travels with the wallet's ID — every subsequent read or update finds its way back to the correct database without any explicit shard parameter.

### Cross-Shard Actions

Cross-shard actions are rejected by default. If an action plans changes that span shards, the executor throws `CrossShardException`. Applications can opt in when needed:

```java
var config = executionConfiguration()
        .allowCrossShard(true)
        .build();
```

When enabled, each involved shard gets its own transaction, and the action-event row is duplicated to each one with the same UUID so every shard contains the full action context.

### Sharding Does Not Provide

- Cross-shard foreign keys.
- Offset/limit pagination, which cannot return correct results across shards. Concrete repositories should use cursor-based pagination instead.
- Distributed transactions; each shard commits independently.

---

## Event Streaming

The events table is a regular table. To turn it into a stream, point a CDC tool at it.

Ekbatan ships with a Debezium → Kafka pipeline:

```
Your App
   │  one transaction per action
   ▼
┌─────────────────────────────────────────────┐
│  wallets, orders, …  +  eventlog.events     │  ← all committed atomically
└─────────────────────────────────────────────┘
   │  outbox rows visible to CDC after commit
   ▼
┌─────────────────────────────────────────────┐
│  Debezium (CDC connector)                   │  ← optional SMT encodes
│                                             │     payload (Avro/Protobuf)
└─────────────────────────────────────────────┘
   │
   ▼
┌─────────────────────────────────────────────┐
│  Kafka — raw topic                          │
└─────────────────────────────────────────────┘
   │  router fans out by event_type
   ▼
┌─────────────────────────────────────────────┐
│  Per-event-type topics                      │
└─────────────────────────────────────────────┘
   │
   ▼
Your consumers
```

The framework writes only **JSON** to the database. Binary encoding (Avro, Protobuf) is performed by **Kafka Connect Single Message Transforms**, not by the application. This separation is deliberate:

- Database transactions remain small and fast; serialization is kept out of the write path.
- Schema mismatches surface in Kafka Connect rather than aborting the application transaction.
- Schema evolution is centralized in the SMT configuration rather than spread across services.

Three consumer-side envelope contracts are published; pick the one matching your wire format:

| Module | Format |
|---|---|
| `ekbatan-event-streaming:action-event:json` | POJO + Jackson |
| `ekbatan-event-streaming:action-event:avro` | generated from `.avsc` |
| `ekbatan-event-streaming:action-event:protobuf` | generated from `.proto` |

Topic naming is conventional:

```
ekbatan.{namespace}.model.{ModelType}
ekbatan.{namespace}.event.{EventType}
```

---

## Built-in Observability

Ekbatan depends only on the OpenTelemetry **API**; no SDK is bundled, so the calls are no-ops with zero overhead unless an SDK is registered at runtime. When an SDK is registered, every action produces a span tree of the following shape:

```
ekbatan.action.execute
├── ekbatan.action.perform
└── ekbatan.action.persist
    └── ekbatan.transaction              (per shard)
        ├── ekbatan.repository           (db.operation.name, batch.size)
        └── ekbatan.event.persist        (event.count)
```

Retries are recorded as span events on the action span. Failures set `StatusCode.ERROR` and attach the exception. Shard identifiers, batch sizes, action names, and entity types are recorded as span attributes, compatible with any standard OpenTelemetry backend (Jaeger, Tempo, Honeycomb, etc.).

---

## Where Ekbatan Fits

Ekbatan is **not** a replacement for Spring, Quarkus, or Micronaut. It does not provide HTTP, dependency injection, configuration, or security — those concerns remain with the host framework.

Ekbatan **is** a replacement for the persistence layer typically built with **Spring Data, Hibernate, JPA, MyBatis, or hand-rolled JDBC + transaction management**. It is intended for applications that need:

- writes to a relational database with strong transactional guarantees,
- a reliable audit trail of business changes,
- propagation of changes to downstream systems via Kafka or a similar broker without dual-write coordination.

Integrations with Spring, Quarkus, and Micronaut are planned. The general approach is to register `ActionExecutor`, `DatabaseRegistry`, and the repositories as beans and inject them where required.

---

## Non-Goals

The following are intentionally outside the scope of the framework:

- **Nested or composable actions.** Operations that must happen together belong in a single Action; independent operations are invoked separately. The action boundary is the transaction boundary.
- **Saga orchestration.** Cross-service workflows are the responsibility of the layer above the framework.
- **Reactive runtime.** Concurrency is handled by Java 25 virtual threads.

---

## Stack & Requirements

- **Java 25** — required for `ScopedValue`, records, and other recent language features.
- **JOOQ 3.20** — type-safe SQL.
- **HikariCP 7** — connection pooling.
- **PostgreSQL, MySQL, or MariaDB** — dialect differences are handled internally.
- *(Optional)* **OpenTelemetry SDK** — for tracing.
- *(Optional)* **Debezium + Kafka Connect** — for event streaming.

The Kafka Connect SMT plugins target Java 21 to match the Kafka Connect runtime; all other modules target Java 25.

---

## Examples

The `ekbatan-integration-tests/` directory contains complete, runnable examples that mirror the snippets in this README. They are the recommended starting point — each subproject is a working application you can study, run, and adapt:

- **`postgres-simple/`** — single-database wallet with create / deposit / close actions. The closest match to the *Example: A Wallet* code above.
- **`postgres-sharded/`** — same wallet model sharded across multiple Postgres instances, including cross-shard tests and shard-aware repositories.
- **`event-pipeline/`** — end-to-end Debezium → Kafka pipeline with JSON, Avro (SMT), and Protobuf (SMT) variants.
- **`core-repo/{pg,mysql,mariadb}/`** — repository CRUD coverage across all three supported databases.

Each subproject ships its own Flyway migrations, JOOQ codegen, and `@Testcontainers` setup, so they double as templates for new applications.

---

## Building & Testing

```bash
./gradlew build            # full build (includes spotlessApply)
./gradlew test             # all tests
./gradlew spotlessApply    # format
./gradlew checkFormat      # verify formatting only
```

Per-database integration tests:

```bash
./gradlew :ekbatan-integration-tests:core-repo:pg:repository:test
./gradlew :ekbatan-integration-tests:core-repo:mysql:repository:test
./gradlew :ekbatan-integration-tests:core-repo:mariadb:repository:test
```

Tests use TestContainers and require Docker to be running.

---

## Project Layout

```
ekbatan/
├── ekbatan-core/                       — the framework
├── ekbatan-annotation-processor/       — @AutoBuilder code generation
├── ekbatan-event-streaming/            — outbox-to-Kafka pipeline
│   ├── action-event/{json,avro,protobuf}      — consumer envelopes
│   └── debezium-smt/{avro,protobuf}           — Kafka Connect SMTs
└── ekbatan-integration-tests/          — end-to-end tests
    ├── core-repo/{pg,mysql,mariadb}
    ├── postgres-simple/
    ├── postgres-sharded/
    └── event-pipeline/
```

For detailed architecture, conventions, and contribution guidelines, see [AGENTS.md](./AGENTS.md).
