package io.ekbatan.core.concurrent;

import java.time.Duration;
import java.util.Optional;

/**
 * A keyed lock: callers acquire mutual exclusion on a {@link String} key. Different keys are
 * independent; the same key serializes access. Implementations cover both in-process
 * (single-JVM) and distributed (cross-JVM) coordination.
 *
 * <p>Callers using typed identifiers (e.g. {@code Id<Wallet>}) should derive the key explicitly
 * — typically by namespacing per type, e.g. {@code "wallet:" + walletId}. This keeps key
 * collisions across unrelated types impossible by construction.
 *
 * <p>Both acquisition methods require a {@code maxHold} duration. The lock is automatically
 * released when {@code maxHold} elapses, even if the caller has not closed the lease — a safety
 * net against a holder that crashes, hangs, or forgets to release. Callers who want indefinite
 * holds within a single JVM should pass a sufficiently large duration; distributed
 * implementations should always be used with a realistic limit.
 *
 * <h2>Reentrancy</h2>
 *
 * <p>All built-in implementations are <b>reentrant per {@code (thread, key)} pair</b>. The same
 * thread can call {@link #acquire(Object, Duration)} or {@link #tryAcquire(Object, Duration,
 * Duration)} on a key it already holds without blocking; the call returns a fresh {@link Lease}
 * instance and increments an internal hold counter. The underlying backend lock is released only
 * when the <em>outermost</em> lease is closed (or the watchdog fires — see below). Each
 * {@code Lease} returned must be closed exactly once; standard try-with-resources handles this
 * naturally.
 *
 * <p><b>The {@code maxHold} parameter is honored on the first acquire only.</b> Re-entries by
 * the same thread on the same key inherit the original watchdog and ignore their {@code maxHold}
 * argument. This matches the rule that the <em>outermost</em> holder commits to the worst-case
 * hold time and that commitment cannot be shortened or extended by inner re-entries; an inner
 * timer cannot pull the rug out from under the outer holder. This contract is stricter than the
 * "last-call-wins" convention used by Redisson and Hazelcast and is the principled default for
 * a time-limited reentrant lock.
 *
 * <p><b>Reentrancy is per-thread, not per-call-stack.</b> A child thread spawned inside a held
 * region is a different identity and will block on the parent's hold like any other waiter.
 * This matches {@link java.util.concurrent.locks.ReentrantLock} semantics exactly.
 *
 * <p><b>Best practice:</b> reentrancy exists to prevent accidental self-deadlock when shared
 * service methods get called transitively from inside a locked region. It is not an invitation
 * to design for nested locking — locks layered across the call stack are a maintainability
 * smell. Keep locking at one well-defined layer when possible.
 *
 * <h2>Implementation guarantees</h2>
 *
 * <p>Implementations vary in their cross-JVM guarantees. An in-process implementation can offer
 * strict FIFO fairness and immediate hand-off; a distributed implementation typically offers
 * best-effort fairness and accepts the operational realities of network partitions and clock
 * drift. Always check the documentation of the specific implementation before relying on a
 * particular property.
 */
public interface KeyedLockProvider extends AutoCloseable {

    /**
     * Acquires the lock on {@code key}, blocking until it becomes available. The returned
     * {@link Lease} is held until {@link Lease#close()} is called, or until {@code maxHold}
     * elapses, whichever happens first.
     *
     * <p>If the calling thread already holds {@code key}, this call returns immediately with a
     * fresh re-entrant lease — no backend round-trip, no blocking. The {@code maxHold} argument
     * is ignored on re-entry; the original outermost watchdog continues to govern the hold
     * limit. See {@link KeyedLockProvider} class docs for the full reentrancy contract.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    Lease acquire(String key, Duration maxHold) throws InterruptedException;

    /**
     * Attempts to acquire the lock on {@code key}, waiting up to {@code maxWait}. Returns the
     * lease if acquired (auto-released after {@code maxHold}), or {@link Optional#empty()} if
     * the wait elapsed without acquiring. Pass {@link Duration#ZERO} for a non-blocking attempt.
     *
     * <p>If the calling thread already holds {@code key}, this call always succeeds immediately
     * with a fresh re-entrant lease — no backend round-trip, no waiting. The {@code maxWait}
     * and {@code maxHold} arguments are both ignored on re-entry; the original outermost
     * watchdog continues to govern the hold limit. See {@link KeyedLockProvider} class docs for
     * the full reentrancy contract.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    Optional<Lease> tryAcquire(String key, Duration maxWait, Duration maxHold) throws InterruptedException;

    /** Releases any resources owned by this lock instance. Default implementation is a no-op. */
    @Override
    default void close() {}

    /**
     * A handle to an acquired lock. Closing releases the lock; closing is idempotent.
     *
     * <p>Each call to {@link KeyedLockProvider#acquire(Object, Duration)} (or
     * {@link KeyedLockProvider#tryAcquire(Object, Duration, Duration)}) returns its own
     * {@code Lease} instance — including re-entries by the same thread on the same key. Each
     * lease must be closed exactly once. The underlying backend lock is released when the
     * outermost lease in the nested chain is closed, or when the {@code maxHold} watchdog
     * fires.
     */
    interface Lease extends AutoCloseable {

        /**
         * True if this lease still holds the lock. Returns false after {@link #close()}, after
         * the {@code maxHold} watchdog has fired (force-releasing the underlying backend lock),
         * or — for inner re-entrant leases — after the watchdog has expired even if the inner
         * lease has not been closed yet.
         */
        boolean isHeld();

        @Override
        void close();
    }
}
