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
 *
 * @param <LOCK_PAYLOAD> backend-specific payload type carried inside each {@link Lease} (e.g. a
 *     Redisson {@code RLock} for the Redis provider, a {@code Connection} for the PostgreSQL
 *     advisory-lock provider). The holder is otherwise oblivious to the backend.
 */
public final class KeyedReentrantHolder<LOCK_PAYLOAD> {

    private static final Logger LOG = LoggerFactory.getLogger(KeyedReentrantHolder.class);
    private static final int RELEASED = -1;

    /**
     * Callback invoked by the holder when the outermost lease closes or the watchdog fires -
     * the backend-specific code that actually releases the underlying lock.
     *
     * @param <LOCK_PAYLOAD> the backend-specific payload type.
     */
    @FunctionalInterface
    public interface ReleaseCallback<LOCK_PAYLOAD> {
        /**
         * Releases the underlying backend lock.
         *
         * @param payload the backend payload registered with the holder at acquire time.
         * @param reason why the holder is releasing the payload.
         */
        void release(LOCK_PAYLOAD payload, ReleaseReason reason);
    }

    /** Why the holder is releasing a backend payload. */
    public enum ReleaseReason {
        /** The outermost lease was closed explicitly. */
        CLOSE,
        /** The max-hold watchdog fired. */
        WATCHDOG
    }

    private record EntryKey(long threadId, String userKey) {}

    private final ConcurrentMap<EntryKey, Holding> map = new ConcurrentHashMap<>();
    private final String watchdogThreadName;

    /**
     * Constructs the holder.
     *
     * @param watchdogThreadName name given to the virtual threads that fire the per-lease watchdogs.
     */
    public KeyedReentrantHolder(String watchdogThreadName) {
        this.watchdogThreadName = watchdogThreadName;
    }

    /**
     * Returns a fresh re-entry {@link Lease} (counter incremented) if the current thread already
     * holds {@code userKey}, else {@link Optional#empty()}. A stale entry left behind by a
     * watchdog is cleaned up in passing.
     *
     * @param userKey the lock key to re-enter on.
     * @return a fresh re-entrant {@link Lease}, or empty if the current thread isn't already holding {@code userKey}.
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
     * {@code (currentThread, userKey)} exists. The same {@link ReleaseCallback} is invoked when
     * the watchdog fires and when the outermost lease is closed.
     *
     * <p>If registration itself fails (e.g. the watchdog thread cannot be started), the
     * callback is invoked once with {@link ReleaseReason#CLOSE} so the backend lock the caller
     * just acquired is released. Any exception thrown by that cleanup is attached as a
     * suppressed exception on the original failure, which is rethrown.
     *
     * @param userKey the lock key being held.
     * @param payload the backend-specific payload associated with the hold.
     * @param maxHold maximum hold time before the watchdog force-releases.
     * @param lockReleaseCallback the callback to invoke to release the backend lock.
     * @return the outermost {@link Lease} for the new hold.
     */
    public Lease register(
            String userKey, LOCK_PAYLOAD payload, Duration maxHold, ReleaseCallback<LOCK_PAYLOAD> lockReleaseCallback) {
        Holding holding = null;
        var mapped = false;
        try {
            var rkey = new EntryKey(Thread.currentThread().threadId(), userKey);
            holding = new Holding(rkey, payload, lockReleaseCallback);
            var registeredHolding = holding;
            var lease = new HeldLease(registeredHolding);
            map.put(rkey, registeredHolding);
            mapped = true;
            registeredHolding.watchdog = Thread.ofVirtual()
                    .name(watchdogThreadName)
                    .start(() -> {
                        try {
                            Thread.sleep(maxHold);
                            if (registeredHolding.markReleasedByWatchdog()) {
                                LOG.warn("Auto-released held lock for key {} (hold limit exceeded)", userKey);
                                map.remove(registeredHolding.rkey, registeredHolding);
                                lockReleaseCallback.release(payload, ReleaseReason.WATCHDOG);
                            }
                        } catch (InterruptedException ignored) {
                        }
                    });
            return lease;
        } catch (RuntimeException | Error e) {
            if (mapped && holding != null) {
                map.remove(holding.rkey, holding);
            }
            if (holding != null) {
                var w = holding.watchdog;
                if (w != null) {
                    w.interrupt();
                }
            }
            try {
                lockReleaseCallback.release(payload, ReleaseReason.CLOSE);
            } catch (RuntimeException | Error cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
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
            holding.lockReleaseCallback.release(holding.payload, ReleaseReason.CLOSE);
        }
    }
}
