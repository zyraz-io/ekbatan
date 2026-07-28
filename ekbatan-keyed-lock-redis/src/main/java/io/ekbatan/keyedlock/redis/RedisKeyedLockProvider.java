package io.ekbatan.keyedlock.redis;

import io.ekbatan.core.concurrent.KeyedLockProvider;
import io.ekbatan.core.concurrent.KeyedReentrantHolder;
import io.ekbatan.core.internal.Validate;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distributed {@link KeyedLockProvider} backed by a Redisson {@link RLock}. Each user-supplied
 * key maps to a Redis key prefixed with the configured namespace; mutual exclusion is
 * coordinated through Redis using Redisson's {@code SET NX} + Lua release machinery.
 *
 * <h2>Why we layer our own reentry tracker on top of Redisson</h2>
 *
 * <p>Redisson's {@code RLock} is itself reentrant per {@code (thread, key)} pair, but its
 * "last-call-wins" semantic for {@code leaseTime} would let an inner re-entry shorten the
 * outer holder's TTL. Ekbatan's {@link KeyedLockProvider} contract requires that the
 * <em>outermost</em> {@code maxHold} governs the watchdog (first-call-wins). To enforce that,
 * the shared {@link KeyedReentrantHolder} keeps a per-thread counter: the first acquire calls
 * Redisson; re-entries bump the counter without touching Redis; the outermost close calls
 * Redisson once.
 *
 * <h2>Watchdog and maxHold</h2>
 *
 * <p>We always pass {@code maxHold} as Redisson's {@code leaseTime} (via the explicit
 * leaseTime overloads of {@code lock} and {@code tryLock}), which disables Redisson's
 * automatic watchdog renewal. The Redis key's TTL becomes the hard upper bound on the hold,
 * matching the {@link KeyedLockProvider#acquire} contract exactly. A local virtual-thread
 * watchdog also fires at {@code maxHold} to flip {@link Lease#isHeld()} to false locally,
 * but it does not send an unlock to Redis; the TTL is the backend release mechanism for
 * timed-out Redis leases.
 *
 * <h2>Multi-master caveat</h2>
 *
 * <p>This provider is correct for single-master Redis deployments (including Sentinel-managed
 * primary-with-replicas, where reads and writes go to the master). It is <b>not</b>
 * Redlock-based and therefore not safe under multi-master Redis (e.g. Active-Active CRDB) or
 * during a master failover that loses the in-memory lock state. For those topologies, a
 * Redlock-based provider would be required instead.
 */
public final class RedisKeyedLockProvider implements KeyedLockProvider {

    private static final Logger LOG = LoggerFactory.getLogger(RedisKeyedLockProvider.class);

    private final RedissonClient redisson;
    private final String keyPrefix;
    private final KeyedReentrantHolder<RedisPayload> holder =
            new KeyedReentrantHolder<>("ekbatan-rediskeyedlock-timeout");

    private RedisKeyedLockProvider(Builder builder) {
        this.redisson = Validate.notNull(builder.redissonClient, "redissonClient is required");
        this.keyPrefix = builder.namespace + ":";
    }

    @Override
    public Lease acquire(String key, Duration maxHold) throws InterruptedException {
        Validate.notBlank(key, "key cannot be blank");
        Validate.notNull(maxHold, "maxHold cannot be null");
        Validate.isTrue(!maxHold.isNegative() && !maxHold.isZero(), "maxHold must be positive");

        var reentered = holder.tryReenter(key);
        if (reentered.isPresent()) {
            return reentered.get();
        }
        var rlock = redisson.getLock(redisKey(key));
        var acquirerThreadId = Thread.currentThread().threadId();
        rlock.lockInterruptibly(leaseMillis(maxHold), TimeUnit.MILLISECONDS);
        return holder.register(key, new RedisPayload(key, rlock, acquirerThreadId), maxHold, this::backendRelease);
    }

    @Override
    public Optional<Lease> tryAcquire(String key, Duration maxWait, Duration maxHold) throws InterruptedException {
        Validate.notBlank(key, "key cannot be blank");
        Validate.notNull(maxWait, "maxWait cannot be null");
        Validate.notNull(maxHold, "maxHold cannot be null");
        Validate.isTrue(!maxWait.isNegative(), "maxWait cannot be negative");
        Validate.isTrue(!maxHold.isNegative() && !maxHold.isZero(), "maxHold must be positive");

        var reentered = holder.tryReenter(key);
        if (reentered.isPresent()) {
            return reentered;
        }
        var rlock = redisson.getLock(redisKey(key));
        var acquirerThreadId = Thread.currentThread().threadId();
        var acquired = rlock.tryLock(maxWait.toMillis(), leaseMillis(maxHold), TimeUnit.MILLISECONDS);
        if (!acquired) {
            return Optional.empty();
        }
        return Optional.of(
                holder.register(key, new RedisPayload(key, rlock, acquirerThreadId), maxHold, this::backendRelease));
    }

    private void backendRelease(RedisPayload payload, KeyedReentrantHolder.ReleaseReason reason) {
        if (reason == KeyedReentrantHolder.ReleaseReason.WATCHDOG) {
            return;
        }
        try {
            // Release using the original acquirer's threadId. A no-arg unlock from a
            // different thread fails Redisson's owner check, while forceUnlock deletes
            // the Redis key unconditionally and can release a new owner after TTL expiry.
            // Redisson's threadId variant checks the stored owner before deleting.
            payload.rlock
                    .unlockAsync(payload.acquirerThreadId)
                    .toCompletableFuture()
                    .join();
        } catch (RuntimeException e) {
            // Redisson surfaces both the benign and the fatal case as a CompletionException from
            // join(), so they are indistinguishable until unwrapped. Deliberately still catching
            // RuntimeException rather than CompletionException: narrowing it would let anything
            // else escape backendRelease and propagate out of the caller's try-with-resources.
            final var cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IllegalMonitorStateException) {
                // The lease's TTL expired before we got here, so Redisson's owner check failed.
                // Nothing is wrong, and nothing is still held.
                LOG.debug("Redis lock for {} was no longer held by this owner", payload.userKey);
            } else {
                // A genuine backend failure - unreachable Redis, failover, timeout. The unlock did
                // NOT happen, so the key stays held until its TTL expires and every other node
                // blocks on it meanwhile. Nothing can release a lock on a server we cannot reach,
                // so the TTL is the only remedy; what matters is that this is not silent. Logging
                // it at DEBUG (as this did) meant a shard stalling for maxHold with no trace.
                LOG.error(
                        "Failed to release Redis lock for key {}; it will remain held until its TTL expires",
                        payload.userKey,
                        cause);
            }
        }
    }

    /**
     * Converts {@code maxHold} to the millisecond lease Redisson expects, never yielding zero.
     *
     * <p>{@link Duration#toMillis()} truncates, so any positive sub-millisecond {@code maxHold}
     * becomes {@code 0} - and Redisson reads a non-positive {@code leaseTime} as "no lease", which
     * activates its own renewal watchdog. The key would then be renewed for as long as the JVM
     * lives instead of expiring, i.e. the exact opposite of the caller's request. Rounding up to
     * 1ms keeps the lease bounded. This mirrors the documented rounding on the MySQL provider,
     * where a sub-second {@code maxWait} rounds up to whole seconds.
     *
     * @param maxHold the requested hold duration; already validated as positive by the callers.
     * @return the lease in milliseconds, at least 1.
     */
    static long leaseMillis(Duration maxHold) {
        return Math.max(1L, maxHold.toMillis());
    }

    private String redisKey(String userKey) {
        return keyPrefix + userKey;
    }

    private record RedisPayload(String userKey, RLock rlock, long acquirerThreadId) {}

    /** Fluent builder for {@link RedisKeyedLockProvider}. Obtain via {@link #redisKeyedLockProvider()}. */
    public static final class Builder {

        private RedissonClient redissonClient;
        private String namespace = "ekbatan-lock";

        private Builder() {}

        /** {@return a fresh builder for {@link RedisKeyedLockProvider}} */
        public static Builder redisKeyedLockProvider() {
            return new Builder();
        }

        /**
         * Sets the Redisson client that backs the distributed lock. Required.
         *
         * @param redissonClient an initialized {@link RedissonClient}; the caller retains ownership and is responsible for closing it.
         * @return this builder, for chaining.
         */
        public Builder redissonClient(RedissonClient redissonClient) {
            this.redissonClient = redissonClient;
            return this;
        }

        /**
         * Prefix for the Redis keys this provider creates (default {@code "ekbatan-lock"}).
         * Lets multiple lock providers - or multiple unrelated apps - share one Redis instance
         * without colliding.
         *
         * @param namespace a non-blank prefix; the colon separator is added automatically.
         * @return this builder, for chaining.
         */
        public Builder namespace(String namespace) {
            this.namespace = Validate.notBlank(namespace, "namespace cannot be blank");
            return this;
        }

        /** {@return a configured {@link RedisKeyedLockProvider}; throws if {@code redissonClient} was not set} */
        public RedisKeyedLockProvider build() {
            return new RedisKeyedLockProvider(this);
        }
    }
}
