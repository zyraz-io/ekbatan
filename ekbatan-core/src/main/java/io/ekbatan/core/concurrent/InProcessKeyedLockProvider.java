package io.ekbatan.core.concurrent;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.Validate;

/**
 * In-process {@link KeyedLockProvider} backed by per-key {@link Semaphore}s. FIFO-fair across
 * threads on the same key; reentrant by {@code (thread, key)} per the {@link KeyedLockProvider}
 * contract.
 *
 * <p>Suitable for coordinating threads within a single JVM. For cross-JVM coordination, use a
 * distributed implementation such as {@link PostgresKeyedLockProvider}.
 *
 * <p>Per-key semaphores are reference-counted and removed from the internal map once they have
 * no active holder or waiter, so the map does not grow unbounded across long-running
 * applications with large key spaces.
 */
public final class InProcessKeyedLockProvider implements KeyedLockProvider {

    private final ConcurrentHashMap<String, LockEntry> locks = new ConcurrentHashMap<>();
    private final KeyedReentrantHolder<InProcessPayload> holder =
            new KeyedReentrantHolder<>("ekbatan-keyedlock-timeout");

    public InProcessKeyedLockProvider() {}

    @Override
    public Lease acquire(String key, Duration maxHold) throws InterruptedException {
        Validate.notBlank(key, "key cannot be blank");
        Validate.notNull(maxHold, "maxHold cannot be null");
        Validate.isTrue(!maxHold.isNegative() && !maxHold.isZero(), "maxHold must be positive");

        var reentered = holder.tryReenter(key);
        if (reentered.isPresent()) {
            return reentered.get();
        }
        var entry = retainEntry(key);
        try {
            entry.semaphore.acquire();
        } catch (InterruptedException e) {
            releaseEntry(key);
            throw e;
        }
        return holder.register(key, new InProcessPayload(key, entry), maxHold, this::backendRelease);
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
        var entry = retainEntry(key);
        boolean acquired = entry.semaphore.tryAcquire(maxWait.toNanos(), TimeUnit.NANOSECONDS);
        if (!acquired) {
            releaseEntry(key);
            return Optional.empty();
        }
        return Optional.of(holder.register(key, new InProcessPayload(key, entry), maxHold, this::backendRelease));
    }

    /** Returns the number of distinct keys currently being tracked. Mostly for diagnostics. */
    public int activeKeyCount() {
        return locks.size();
    }

    private void backendRelease(InProcessPayload payload) {
        payload.entry.semaphore.release();
        releaseEntry(payload.userKey);
    }

    private LockEntry retainEntry(String key) {
        return locks.compute(key, (k, existing) -> {
            var e = existing != null ? existing : new LockEntry();
            e.refCount.incrementAndGet();
            return e;
        });
    }

    private void releaseEntry(String key) {
        locks.compute(key, (k, existing) -> {
            if (existing == null) {
                return null;
            }
            return existing.refCount.decrementAndGet() == 0 ? null : existing;
        });
    }

    private record InProcessPayload(String userKey, LockEntry entry) {}

    private static final class LockEntry {
        final Semaphore semaphore = new Semaphore(1, true); // fair: FIFO order for waiters
        final AtomicInteger refCount = new AtomicInteger(0);
    }
}
