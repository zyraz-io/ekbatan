package io.ekbatan.core.concurrent;

import java.time.Duration;
import java.util.Optional;

/**
 * A keyed lock: callers acquire mutual exclusion on an arbitrary {@link Object} key. Different
 * keys are independent; the same key serializes access. Implementations cover both in-process
 * (single-JVM) and distributed (cross-JVM) coordination.
 *
 * <p>Both acquisition methods require a {@code maxHold} duration. The lock is automatically
 * released when {@code maxHold} elapses, even if the caller has not closed the lease — a safety
 * net against a holder that crashes, hangs, or forgets to release. Callers who want indefinite
 * holds within a single JVM should pass a sufficiently large duration; distributed
 * implementations should always be used with a realistic limit.
 *
 * <p><b>Implementations vary in their guarantees.</b> An in-process implementation can offer
 * strict FIFO fairness and immediate hand-off; a distributed implementation typically offers
 * best-effort fairness and accepts the operational realities of network partitions and clock
 * drift. Always check the documentation of the specific implementation before relying on a
 * particular property.
 */
public interface KeyedLock extends AutoCloseable {

    /**
     * Acquires the lock on {@code key}, blocking until it becomes available. The returned
     * {@link Lease} is held until {@link Lease#close()} is called, or until {@code maxHold}
     * elapses, whichever happens first.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    Lease acquire(Object key, Duration maxHold) throws InterruptedException;

    /**
     * Attempts to acquire the lock on {@code key}, waiting up to {@code maxWait}. Returns the
     * lease if acquired (auto-released after {@code maxHold}), or {@link Optional#empty()} if
     * the wait elapsed without acquiring. Pass {@link Duration#ZERO} for a non-blocking attempt.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    Optional<Lease> tryAcquire(Object key, Duration maxWait, Duration maxHold) throws InterruptedException;

    /** Releases any resources owned by this lock instance. Default implementation is a no-op. */
    @Override
    default void close() {}

    /**
     * A handle to an acquired lock. Closing releases the lock; closing is idempotent.
     */
    interface Lease extends AutoCloseable {

        /** True if this lease still holds the lock; false after close or hold-limit expiry. */
        boolean isHeld();

        @Override
        void close();
    }
}
