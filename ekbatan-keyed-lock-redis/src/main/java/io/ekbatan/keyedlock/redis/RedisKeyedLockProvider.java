package io.ekbatan.keyedlock.redis;

import io.ekbatan.core.concurrent.KeyedLockProvider;
import io.ekbatan.core.concurrent.KeyedReentrantHolder;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.Validate;
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
 * watchdog also fires at {@code maxHold} to flip {@link Lease#isHeld()} to false locally so
 * callers see consistent state if the Redis key TTL elapsed first.
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
        rlock.lockInterruptibly(maxHold.toMillis(), TimeUnit.MILLISECONDS);
        return holder.register(key, new RedisPayload(key, rlock), maxHold, this::backendRelease);
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
        var acquired = rlock.tryLock(maxWait.toMillis(), maxHold.toMillis(), TimeUnit.MILLISECONDS);
        if (!acquired) {
            return Optional.empty();
        }
        return Optional.of(holder.register(key, new RedisPayload(key, rlock), maxHold, this::backendRelease));
    }

    private void backendRelease(RedisPayload payload) {
        try {
            // Use forceUnlock to bypass Redisson's threadId check — by the time the watchdog
            // fires we may be on a different (virtual) thread than the original acquirer.
            // forceUnlock deletes the Redis key unconditionally; safe here because our own
            // counter already arbitrated which path performs the release.
            payload.rlock.forceUnlock();
        } catch (RuntimeException e) {
            LOG.debug("Tried to unlock Redis lock for {} but it was no longer held", payload.userKey, e);
        }
    }

    private String redisKey(String userKey) {
        return keyPrefix + userKey;
    }

    private record RedisPayload(String userKey, RLock rlock) {}

    public static final class Builder {

        private RedissonClient redissonClient;
        private String namespace = "ekbatan-lock";

        private Builder() {}

        public static Builder redisKeyedLockProvider() {
            return new Builder();
        }

        public Builder redissonClient(RedissonClient redissonClient) {
            this.redissonClient = redissonClient;
            return this;
        }

        /**
         * Prefix for the Redis keys this provider creates (default {@code "ekbatan-lock"}).
         * Lets multiple lock providers — or multiple unrelated apps — share one Redis instance
         * without colliding.
         */
        public Builder namespace(String namespace) {
            this.namespace = Validate.notBlank(namespace, "namespace cannot be blank");
            return this;
        }

        public RedisKeyedLockProvider build() {
            return new RedisKeyedLockProvider(this);
        }
    }
}
