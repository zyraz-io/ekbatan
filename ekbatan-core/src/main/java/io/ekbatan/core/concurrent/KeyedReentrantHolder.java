package io.ekbatan.core.concurrent;

import io.ekbatan.core.concurrent.KeyedLockProvider.Lease;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-{@code (thread, key)} hold tracker shared by every reentrant {@link KeyedLockProvider}
 * implementation. Owns the counter, the watchdog thread, the release-arbitration CAS, and the
 * {@link Lease} wrapper so each backend only has to define how to acquire and how to release
 * the underlying resource.
 *
 * <p>The counter doubles as an alive flag: positive values are the number of nested holds; the
 * sentinel {@code RELEASED} ({@code -1}) means the underlying backend lock has been released
 * (either by the outermost close or by the watchdog) and no further re-entries are permitted
 * on this hold.
 *
 * <p>Same thread + same key acquires never contend with each other (sequential by thread). The
 * only contention is between a thread re-entering / closing and the watchdog firing on a
 * different (virtual) thread, arbitrated by CAS on the counter.
 */
public final class KeyedReentrantHolder<LOCK_PAYLOAD> {

    private static final Logger LOG = LoggerFactory.getLogger(KeyedReentrantHolder.class);
    private static final int RELEASED = -1;

    @FunctionalInterface
    public interface ReleaseCallback<LOCK_PAYLOAD> {
        void release(LOCK_PAYLOAD payload);
    }

    private record EntryKey(long threadId, String userKey) {}

    private final ConcurrentMap<EntryKey, Holding> map = new ConcurrentHashMap<>();
    private final String watchdogThreadName;

    public KeyedReentrantHolder(String watchdogThreadName) {
        this.watchdogThreadName = watchdogThreadName;
    }

    /**
     * Returns a fresh re-entry {@link Lease} (counter incremented) if the current thread already
     * holds {@code userKey}, else {@link Optional#empty()}. A stale entry left behind by a
     * watchdog is cleaned up in passing.
     */
    public Optional<Lease> tryReenter(String userKey) {
        var rkey = new EntryKey(Thread.currentThread().threadId(), userKey);
        var existing = map.get(rkey);
        if (existing == null) {
            return Optional.empty();
        }
        if (existing.tryIncrement()) {
            return Optional.of(new HeldLease(existing));
        }
        map.remove(rkey, existing);
        return Optional.empty();
    }

    /**
     * Registers a freshly-acquired hold, starts its watchdog, and returns the outermost
     * {@link Lease}. Must only be called when {@link #tryReenter(String)} just returned empty
     * for the same key on the same thread, so that no other holding for
     * {@code (currentThread, userKey)} exists. The same {@link ReleaseCallback} is invoked both
     * when the watchdog fires and when the outermost lease is closed.
     */
    public Lease register(
            String userKey, LOCK_PAYLOAD payload, Duration maxHold, ReleaseCallback<LOCK_PAYLOAD> lockReleaseCallback) {
        var rkey = new EntryKey(Thread.currentThread().threadId(), userKey);
        var holding = new Holding(rkey, payload, lockReleaseCallback);
        map.put(rkey, holding);
        holding.watchdog = Thread.ofVirtual().name(watchdogThreadName).start(() -> {
            try {
                Thread.sleep(maxHold);
                if (holding.markReleasedByWatchdog()) {
                    LOG.warn("Auto-released held lock for key {} (hold limit exceeded)", userKey);
                    map.remove(rkey, holding);
                    lockReleaseCallback.release(payload);
                }
            } catch (InterruptedException ignored) {
            }
        });
        return new HeldLease(holding);
    }

    private final class Holding {

        private final EntryKey rkey;
        private final LOCK_PAYLOAD payload;
        private final ReleaseCallback<LOCK_PAYLOAD> lockReleaseCallback;
        private final AtomicInteger count = new AtomicInteger(1);
        private volatile Thread watchdog;

        Holding(EntryKey rkey, LOCK_PAYLOAD payload, ReleaseCallback<LOCK_PAYLOAD> lockReleaseCallback) {
            this.rkey = rkey;
            this.payload = payload;
            this.lockReleaseCallback = lockReleaseCallback;
        }

        boolean isHeld() {
            return count.get() != RELEASED;
        }

        boolean tryIncrement() {
            while (true) {
                int v = count.get();
                if (v == RELEASED) {
                    return false;
                }
                if (count.compareAndSet(v, v + 1)) {
                    return true;
                }
            }
        }

        /** Returns true iff this call drove count to {@code RELEASED} (the outermost close). */
        boolean decrementAndMaybeRelease() {
            while (true) {
                int v = count.get();
                if (v == RELEASED) {
                    return false; // watchdog already won
                }
                if (v == 1) {
                    if (count.compareAndSet(1, RELEASED)) {
                        return true;
                    }
                    continue;
                }
                if (count.compareAndSet(v, v - 1)) {
                    return false;
                }
            }
        }

        boolean markReleasedByWatchdog() {
            while (true) {
                int v = count.get();
                if (v == RELEASED) {
                    return false;
                }
                if (count.compareAndSet(v, RELEASED)) {
                    return true;
                }
            }
        }
    }

    private final class HeldLease implements Lease {

        private final Holding holding;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        HeldLease(Holding holding) {
            this.holding = holding;
        }

        @Override
        public boolean isHeld() {
            return !closed.get() && holding.isHeld();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (!holding.decrementAndMaybeRelease()) {
                return;
            }
            map.remove(holding.rkey, holding);
            var w = holding.watchdog;
            if (w != null) {
                w.interrupt();
            }
            holding.lockReleaseCallback.release(holding.payload);
        }
    }
}
