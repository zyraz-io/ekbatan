package io.ekbatan.core.concurrent;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-process {@link KeyedLock} backed by per-key {@link Semaphore}s. FIFO-fair, non-reentrant.
 *
 * <p>Suitable for coordinating threads within a single JVM. For cross-JVM coordination, use a
 * distributed implementation such as {@link PostgresAdvisoryKeyedLock}.
 *
 * <p>Per-key entries are reference-counted and removed from the internal map once they have no
 * active holder or waiter, so the map does not grow unbounded across long-running applications
 * with large key spaces.
 *
 * <p>The {@code maxHold} timeout is enforced by spawning one virtual thread per acquired lease
 * that sleeps for the requested duration and then force-releases the lock if the caller has not
 * closed the lease first. Closing the lease early interrupts the timeout thread.
 */
public final class InProcessKeyedLock implements KeyedLock {

    private static final Logger LOG = LoggerFactory.getLogger(InProcessKeyedLock.class);

    private final ConcurrentHashMap<Object, LockEntry> locks = new ConcurrentHashMap<>();

    public InProcessKeyedLock() {}

    @Override
    public Lease acquire(Object key, Duration maxHold) throws InterruptedException {
        Validate.notNull(key, "key cannot be null");
        Validate.notNull(maxHold, "maxHold cannot be null");
        Validate.isTrue(!maxHold.isNegative() && !maxHold.isZero(), "maxHold must be positive");

        var entry = retainEntry(key);
        try {
            entry.semaphore.acquire();
        } catch (InterruptedException e) {
            releaseEntry(key);
            throw e;
        }
        return startTimeout(new InProcessLease(this, key, entry), maxHold);
    }

    @Override
    public Optional<Lease> tryAcquire(Object key, Duration maxWait, Duration maxHold) throws InterruptedException {
        Validate.notNull(key, "key cannot be null");
        Validate.notNull(maxWait, "maxWait cannot be null");
        Validate.notNull(maxHold, "maxHold cannot be null");
        Validate.isTrue(!maxWait.isNegative(), "maxWait cannot be negative");
        Validate.isTrue(!maxHold.isNegative() && !maxHold.isZero(), "maxHold must be positive");

        var entry = retainEntry(key);
        boolean acquired = entry.semaphore.tryAcquire(maxWait.toNanos(), TimeUnit.NANOSECONDS);
        if (!acquired) {
            releaseEntry(key);
            return Optional.empty();
        }
        return Optional.of(startTimeout(new InProcessLease(this, key, entry), maxHold));
    }

    /** Returns the number of distinct keys currently being tracked. Mostly for diagnostics. */
    public int activeKeyCount() {
        return locks.size();
    }

    private InProcessLease startTimeout(InProcessLease lease, Duration maxHold) {
        var thread = Thread.ofVirtual().name("ekbatan-keyedlock-timeout").start(() -> {
            try {
                Thread.sleep(maxHold);
                lease.expire();
            } catch (InterruptedException ignored) {
                // user closed the lease in time; nothing to do
            }
        });
        lease.bindTimeoutThread(thread);
        return lease;
    }

    private LockEntry retainEntry(Object key) {
        return locks.compute(key, (k, existing) -> {
            var e = existing != null ? existing : new LockEntry();
            e.refCount.incrementAndGet();
            return e;
        });
    }

    private void releaseEntry(Object key) {
        locks.compute(key, (k, existing) -> {
            if (existing == null) {
                return null;
            }
            return existing.refCount.decrementAndGet() == 0 ? null : existing;
        });
    }

    void doRelease(Object key, LockEntry entry) {
        entry.semaphore.release();
        releaseEntry(key);
    }

    private static final class LockEntry {
        final Semaphore semaphore = new Semaphore(1, true); // fair: FIFO order for waiters
        final AtomicInteger refCount = new AtomicInteger(0);
    }

    private static final class InProcessLease implements Lease {

        private final InProcessKeyedLock owner;
        private final Object key;
        private final LockEntry entry;
        private final AtomicBoolean released = new AtomicBoolean(false);
        private volatile Thread timeoutThread;

        InProcessLease(InProcessKeyedLock owner, Object key, LockEntry entry) {
            this.owner = owner;
            this.key = key;
            this.entry = entry;
        }

        void bindTimeoutThread(Thread thread) {
            this.timeoutThread = thread;
        }

        @Override
        public boolean isHeld() {
            return !released.get();
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                interruptTimeout();
                owner.doRelease(key, entry);
            }
        }

        void expire() {
            if (released.compareAndSet(false, true)) {
                LOG.warn("InProcessKeyedLock auto-released held lock for key {} (hold limit exceeded)", key);
                owner.doRelease(key, entry);
            }
        }

        private void interruptTimeout() {
            var t = timeoutThread;
            if (t != null) {
                t.interrupt();
            }
        }
    }
}
