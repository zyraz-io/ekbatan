# TransactionManager

`ActionExecutor` is the right entry point for **business operations** — anything that mutates a domain object and emits events. But sometimes you need raw transactional database access outside that pipeline: an admin script that backfills a column, a startup hook that reconciles two tables, a custom multi-step read that doesn't fit the Action shape, or batch maintenance that has no associated event.

For those, the framework gives you `TransactionManager` directly. It's the same primitive `ActionExecutor.persistChanges()` uses internally — one transaction, one JDBC connection, auto commit-or-rollback, with the connection bound to the calling thread via `ScopedValue` so any repository methods you invoke from inside automatically join the transaction.

## What it owns

- **One JDBC pool pair** (primary + secondary `ConnectionProvider`) for one shard's database.
- **A `SQLDialect`** so JOOQ knows what to render.
- **A `ShardIdentifier`** (defaults to `ShardIdentifier.DEFAULT` when not sharded) — used to label the OTel span and to let `DatabaseRegistry` route by shard.
- **A `ScopedValue<Transaction>`** that holds the in-flight transaction's `Connection` + `DSLContext` for the duration of an `inTransaction(...)` call.

You typically don't construct `TransactionManager` yourself in application code — you reach for one through `DatabaseRegistry`:

```java
TransactionManager tm = databaseRegistry.defaultTransactionManager();
// or, on a sharded system:
TransactionManager mexico = databaseRegistry.transactionManager(MEXICO_SHARD);
```

## The `inTransaction(...)` family

Two flavors, mirrored for `Function` (returning a value) and `Consumer` (no return), checked-vs-unchecked variants:

```java
// Returns a value, propagates RuntimeException
<R> R inTransaction(Function<Transaction, R> work);
   void inTransaction(Consumer<Transaction> work);

// Returns a value, allows the block to throw checked exceptions
<R> R inTransactionChecked(CheckedFunction<Transaction, R> work) throws Exception;
   void inTransactionChecked(CheckedConsumer<Transaction> work) throws Exception;
```

What each call does, in order:

1. Borrow a `Connection` from the **primary** `ConnectionProvider`.
2. Save its current `autoCommit`, set `autoCommit = false`.
3. Build a JOOQ `DSLContext` over the connection and bind it (along with the `Connection`) into the `ScopedValue<Transaction>`.
4. Run your lambda with the bound `Transaction` as argument.
5. On normal return: `commit()`, restore `autoCommit`, release the connection back to the pool.
6. On exception: `rollback()` (errors during rollback are logged but don't mask the original throwable), restore `autoCommit`, release the connection, rethrow.

There is **no nesting**. Calling `tm.inTransaction(...)` from inside an already-open `inTransaction(...)` on the same `tm` will throw — the framework deliberately doesn't simulate nested transactions or savepoints. If you need composition, structure your code to do all the work inside one outer block.

## Direct usage — a worked example

A backfill script that adds a default currency to wallets that don't have one yet:

```java
DatabaseRegistry registry = …;          // wired by DI or by hand
TransactionManager tm = registry.defaultTransactionManager();

tm.inTransactionChecked(transaction -> {
    DSLContext db = transaction.dslContext();

    // Find wallets missing a currency
    List<UUID> orphans = db.select(WALLETS.ID)
            .from(WALLETS)
            .where(WALLETS.CURRENCY.isNull())
            .forUpdate()                 // hold them for the duration of this transaction
            .fetch(WALLETS.ID);

    if (orphans.isEmpty()) return;

    // Patch each one and write an audit row in the same transaction
    db.update(WALLETS)
            .set(WALLETS.CURRENCY, "EUR")
            .where(WALLETS.ID.in(orphans))
            .execute();

    db.insertInto(AUDIT_LOG, AUDIT_LOG.AT, AUDIT_LOG.NOTE, AUDIT_LOG.AFFECTED_COUNT)
            .values(Instant.now(), "currency backfill", orphans.size())
            .execute();
});
```

Either both writes commit together, or neither does. Same atomicity guarantee an Action gives you, with none of the Action machinery (no `ActionPlan`, no `eventlog.events` row, no retries on `StaleRecordException`).

The lambda receives a `Transaction` value. From it you can pull the `DSLContext` (`transaction.dslContext()`) for raw JOOQ, or the underlying `Connection` (`transaction.connection()`) if you genuinely need JDBC.

## Repository writes join automatically

Because the open transaction is bound into a `ScopedValue<Transaction>`, inherited repository writes and custom queries that use `txDbElseDb(...)` reuse the open transaction's connection. No need to pass `transaction` or `db` around manually for writes:

```java
WalletRepository walletRepository = …;

tm.inTransactionChecked(transaction -> {
    // Inherited reads use primary connections by design. If this read
    // must see uncommitted writes from this block, use transaction.dslContext()
    // or a custom repository query that uses txDbElseDb(...).
    Wallet wallet = walletRepository.getById(walletId);

    // Custom write inside the repository uses txDbElseDb() — same connection.
    walletRepository.markAllSettled(List.of(walletId));

    // Direct JOOQ on the same DSLContext — same connection.
    transaction.dslContext()
            .update(WALLETS)
            .set(WALLETS.STATE, "ARCHIVED")
            .where(WALLETS.ID.eq(walletId))
            .execute();
});
```

This is what makes the "use repositories outside actions" path painless. You don't *have* to drop into raw JOOQ for writes; inherited write methods and custom `txDbElseDb(...)` writes participate in the same transaction. Reads are a choice: inherited reads use primary committed state, `readonlyDb(...)` is for explicit replica reads, and `txDbElseDb(...)` or `transaction.dslContext()` is for custom reads that must see the current transaction.

## When NOT to use it directly

If your operation is a **domain operation** — it mutates a `Model` and emits a `ModelEvent` — use an `Action` instead. The Action path gets you:

- The `eventlog.events` row written atomically with the domain row (the whole point of the framework).
- Optimistic-lock retries on `StaleRecordException`.
- OpenTelemetry span hierarchy (`action.execute` → `action.persist` → `transaction` → `repository` / `event.persist`).
- Cross-shard validation and the `allowCrossShard` knob.
- Fan-out into the local-event-handler and Debezium pipelines.

`tm.inTransaction(...)` is the escape hatch — it gives you a transaction without any of that. Reach for it when the operation legitimately doesn't fit the Action shape:

- Boot-time / shutdown-time initialization (apply default rows, run a sanity check).
- Admin / ops scripts (one-off backfills, manual fixes, data exports).
- Custom multi-step reads where you want repeatable-read consistency without writing anything.
- Heavy batch maintenance jobs where emitting one event per row would create useless outbox volume.

If you find yourself reaching for `tm.inTransaction(...)` for normal business work, that's a signal — the operation probably wants to be an `Action` instead.

## Read-only access (no transaction needed)

If you only need to read, you don't have to open a transaction at all. `DatabaseRegistry` exposes `DSLContext`s directly:

```java
// Replica reads — for list / search queries that tolerate replication lag
DSLContext readonly = registry.readonlyDb(shard);    // or readonlyDb() for the default shard

// Primary reads — for queries that must see the freshest committed state
DSLContext primary  = registry.primaryDb(shard);
```

These are bare connections from the pool — no transaction is opened, no `ScopedValue` is bound. Use them when nothing about your read needs the all-or-nothing semantics.

## Cross-shard

`TransactionManager` is **per-shard**. There's no distributed 2PC: if you need writes across multiple shards atomically, you can't. The Action layer's `allowCrossShard=true` mode runs each shard's transaction separately and accepts the partial-failure risk; if you need the same outside an Action, write the same pattern by hand:

```java
registry.transactionManager(GLOBAL_SHARD).inTransactionChecked(_ -> { /* work A */ });
registry.transactionManager(MEXICO_SHARD).inTransactionChecked(_ -> { /* work B */ });
// If work B fails, work A is already committed.
```

For the much more common single-shard case, just pick the right `TransactionManager` from the registry and you're done.

## Tracing

`tm.inTransaction(...)` produces an `ekbatan.transaction` OTel span with `ekbatan.shard.group` / `ekbatan.shard.member` attributes set automatically (the `TransactionManager` knows its own `ShardIdentifier`). On rollback the span is marked `ERROR`. See [Observability](../runtime/observability.md) for the full attribute table.

## See also

- [Actions](../concepts/actions.md) — the recommended path for business operations; uses `TransactionManager` internally
- [Repositories on JOOQ](repositories.md) — `db()` / `readonlyDb()` / `txDb()` / `txDbElseDb()`, which interact with whatever transaction is currently open
- [Sharding](sharding.md) — `DatabaseRegistry` indexes one `TransactionManager` per shard
- [Observability](../runtime/observability.md) — the `ekbatan.transaction` span
