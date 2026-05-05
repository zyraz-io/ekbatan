# Pessimistic locking via `KeyedLockProvider`

Most of Ekbatan's write path uses **optimistic** locking — every update carries `WHERE version = ?` and conflicting writes surface as `StaleRecordException` for the executor to retry. That works well at low-to-medium contention. Some operations don't fit that model:

- **At-most-once side effects** around external systems — a webhook handler that calls a payment API where retrying would charge twice.
- **Single-flight execution** — a reconciliation job that should run on exactly one node at a time.
- **Hot-key write paths** — a wallet that takes 1000 deposits/sec where retry-on-conflict thrashes more than it succeeds.

For these, `KeyedLockProvider` is a key-scoped mutex with a uniform contract across five backends. Two acquirers using the same key are mutually exclusive (the second waits up to `maxWait` for the first to release); acquirers using different keys don't block each other.

## The contract

```java
public interface KeyedLockProvider {
    Lease            acquire(String key, Duration maxHold) throws InterruptedException;
    Optional<Lease> tryAcquire(String key, Duration maxWait, Duration maxHold) throws InterruptedException;

    interface Lease extends AutoCloseable {
        boolean isHeld();
        @Override void close();
    }
}
```

- `acquire(key, maxHold)` — blocking. Waits indefinitely until acquired (or thread interrupt). The lease auto-releases when `maxHold` elapses, regardless of whether the holder closed it.
- `tryAcquire(key, maxWait, maxHold)` — bounded-wait. Returns `Optional.empty()` if the wait times out.
- `key` is a `String`. **Namespace it per type** when locking on entity IDs (e.g. `"wallet:" + walletId`) so two unrelated ID spaces never collide.
- Closing the lease releases the lock. Use `try-with-resources`.

A wallet deposit, serialized per-wallet:

```java
public class WalletDepositAction extends Action<WalletDepositAction.Params, Wallet> {

    public record Params(Id<Wallet> walletId, BigDecimal amount) {}

    private final WalletRepository walletRepo;
    private final KeyedLockProvider lockProvider;

    public WalletDepositAction(Clock clock, WalletRepository walletRepo, KeyedLockProvider lockProvider) {
        super(clock);
        this.walletRepo = walletRepo;
        this.lockProvider = lockProvider;
    }

    @Override
    protected Wallet perform(Principal principal, Params params) throws InterruptedException {
        try (var lease = lockProvider.tryAcquire("wallet:" + params.walletId, Duration.ofSeconds(2), Duration.ofSeconds(10))
                .orElseThrow(() -> new IllegalStateException(
                        "Wallet " + params.walletId + " is busy; try again later"))) {
            var wallet = walletRepo.getById(params.walletId.getValue());
            return plan().update(wallet.copy()
                    .balance(wallet.balance.add(params.amount))
                    .build());
        }
    }
}
```

Three things worth highlighting:

- **`maxWait` (2s) bounds how long the caller will block** before giving up and surfacing a domain error. Without it (e.g. the no-wait `acquire(key, maxHold)` variant), a caller would queue indefinitely behind any prior holders.
- **`maxHold` (10s) is a safety net, not a hint.** If the action overruns, the lock auto-releases — capping the blast radius of a hung holder regardless of what the calling thread is doing.
- **Each acquire borrows its own JDBC connection** (for the SQL-backed providers) for the lifetime of the lease and for the wait. For lock-heavy workloads, point the provider at a dedicated pool — see [the dedicated pool recipe below](#dedicated-pool-via-the-lockconfig-slot).

## Reentrancy contract — uniform across all five backends

Same thread + same key acquires re-enter without blocking. The underlying backend lock is released only when the **outermost** lease is closed (or the `maxHold` watchdog fires).

The first acquire's `maxHold` governs the watchdog — re-entries' `maxHold` arguments are ignored. This is **stricter than Redisson/Hazelcast's "last-call-wins"** convention and prevents an inner re-entry from shortening the outer holder's commitment.

Reentrancy is **per-thread, not per-call-stack**. A child thread spawned inside a held region is a different identity and will block.

This contract is enforced by a shared internal helper, `KeyedReentrantHolder`, which owns the per-`(thread, key)` counter, a virtual-thread watchdog, and the release-arbitration CAS. Each backend only has to implement low-level `acquire`/`release`.

## Five backends

| Provider | Scope | Backend | Hold | Notes |
|---|---|---|---|---|
| `InProcessKeyedLockProvider` | single JVM | per-key fair `Semaphore` | watchdog | FIFO. No JDBC connection consumed. |
| `PostgresKeyedLockProvider` | cross-JVM | `pg_advisory_lock` (session-scoped) | session + watchdog | Auto-released if the session terminates (process crash, network drop). Borrows a connection per lease. |
| `MariaDBKeyedLockProvider` | cross-JVM | `GET_LOCK(...)` | session + watchdog | MariaDB 12.x rejects negative timeout — `Integer.MAX_VALUE` (≈68 years) is the wait-forever sentinel. |
| `MySQLKeyedLockProvider` | cross-JVM | `GET_LOCK(...)` | session + watchdog | Sub-second `maxWait` rounds up to whole seconds (MySQL precision limit). |
| `RedisKeyedLockProvider` | cross-JVM | Redisson `RLock` | TTL + watchdog | Sub-millisecond hand-off. Lives in `ekbatan-keyed-lock-redis`. Single-master only — **not** Redlock-based. |

All five honor the same reentrancy contract via `KeyedReentrantHolder`. The Redis variant explicitly disables Redisson's own watchdog (passes `maxHold` as Redisson `leaseTime`) and uses a local virtual-thread watchdog so the framework's first-call-wins semantics win over Redisson's last-call-wins default.

### Wiring up a backend

The SQL-backed providers all take a `ConnectionProvider`; Redis takes a `RedissonClient`:

```java
import static io.ekbatan.core.concurrent.PostgresKeyedLockProvider.Builder.postgresKeyedLockProvider;
import static io.ekbatan.core.persistence.ConnectionProvider.hikariConnectionProvider;

var lockProvider = postgresKeyedLockProvider()
        .connectionProvider(hikariConnectionProvider(lockDataSourceConfig))
        .build();
```

```java
import static io.ekbatan.keyedlock.redis.RedisKeyedLockProvider.Builder.redisKeyedLockProvider;

var lockProvider = redisKeyedLockProvider()
        .redissonClient(redisson)
        .namespace("my-app-locks")    // optional, default "ekbatan-lock"
        .build();
```

The same builder shape works for `MariaDBKeyedLockProvider`, `MySQLKeyedLockProvider`, and `InProcessKeyedLockProvider`.

## Dedicated pool via the `lockConfig` slot

Each held lease (and each thread blocked waiting for one) pins a JDBC connection for its entire lifetime. For lock-heavy workloads, **point the provider at a dedicated pool** so locks don't starve normal queries.

Add a user-defined `lockConfig:` entry to the relevant member in your `ShardingConfig`:

```yaml
sharding:
  groups:
    - group: 0
      members:
        - member: 0
          configs:
            primaryConfig:
              jdbcUrl: jdbc:postgresql://primary-eu-1:5432/db
              username: app
              password: ${APP_PASSWORD}
              maximumPoolSize: 20

            secondaryConfig:
              jdbcUrl: jdbc:postgresql://replica-eu-1:5432/db
              username: app
              password: ${APP_PASSWORD}
              maximumPoolSize: 10

            # Dedicated pool for KeyedLockProvider — keeps lock acquisitions
            # from competing for connections with normal queries.
            lockConfig:
              jdbcUrl: jdbc:postgresql://primary-eu-1:5432/db   # same DB; locks must coordinate on the same instance
              username: app
              password: ${APP_PASSWORD}
              maximumPoolSize: 40                # per-instance — sized for the concurrent leases this instance holds
              minimumIdle: 5
              leakDetectionThreshold: 120000     # set comfortably above your largest expected maxHold
```

Pull it out and wire the provider:

```java
var lockDataSourceConfig = shardingConfig.groups.get(0).members.get(0)
        .configFor("lockConfig")
        .orElseThrow();

var lockProvider = postgresKeyedLockProvider()
        .connectionProvider(hikariConnectionProvider(lockDataSourceConfig))
        .build();
```

## Optimistic vs pessimistic — picking one

The same wallet deposit can be implemented either way:

- **Optimistic**: `walletRepo.getById(...)` → `wallet.deposit(amount)` → `plan().update(...)`. On a hot wallet, every concurrent caller hits `StaleRecordException` and the executor retries — which adds tail latency proportional to the contention.
- **Pessimistic**: take a `KeyedLockProvider` lease keyed on the wallet ID, then read-modify-write inside it. Callers wait briefly for the lease and then succeed on their first attempt. The retry loop disappears.

On hot keys the pessimistic version is usually the better trade. On low-contention paths the optimistic version is simpler and avoids the connection-per-holder cost.

## Caveat: not the right primitive at very high concurrency

`KeyedLockProvider` fits coarse-grained coordination (single-flight cron jobs, admin actions, per-shard locks) and low-to-medium contention on the write path. At higher concurrency, every held lease — and every thread blocked waiting for one — pins a JDBC connection for its entire lifetime, which can quickly demand more connections than your database is sized for.

If you're approaching that regime, consider:

- **Falling back to optimistic locking** with shorter retry delays — usually cheaper at high concurrency.
- **Application-level batching** to coalesce contending writes.
- **The Redis-backed provider**, which doesn't pin JDBC connections.

## See also

- [Models and Entities](../concepts/models-and-entities.md#optimistic-locking-always) — the optimistic locking baseline
- [Sharding](sharding.md) — where the `lockConfig` slot lives
- [Distributed background jobs](../jobs/distributed-jobs.md) — uses `KeyedLockProvider`-style cluster exclusivity through db-scheduler instead
