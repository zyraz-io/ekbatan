package io.ekbatan.core.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Reentrancy contract tests for {@link InProcessKeyedLockProvider}. Same scenarios are mirrored
 * by the per-backend integration tests so the contract is uniform across providers.
 */
class InProcessKeyedLockProviderReentrancyTest {

    private static final Duration FIVE_MIN = Duration.ofMinutes(5);

    @Test
    void same_thread_can_reacquire_same_key_without_blocking() throws Exception {
        var lock = new InProcessKeyedLockProvider();
        var key = uniqueKey();

        try (var outer = lock.acquire(key, FIVE_MIN);
                var inner = lock.acquire(key, FIVE_MIN)) {
            assertThat(outer.isHeld()).isTrue();
            assertThat(inner.isHeld()).isTrue();
        }

        // After both close, key is fully released.
        try (var fresh = lock.acquire(key, FIVE_MIN)) {
            assertThat(fresh.isHeld()).isTrue();
        }
    }

    @Test
    void inner_close_does_not_release_underlying_lock() throws Exception {
        var lock = new InProcessKeyedLockProvider();
        var key = uniqueKey();

        try (var outer = lock.acquire(key, FIVE_MIN)) {
            var inner = lock.acquire(key, FIVE_MIN);
            inner.close();

            assertThat(outer.isHeld()).isTrue();
            assertThat(otherThreadCanAcquire(lock, key)).isFalse();
        }
    }

    @Test
    void outer_close_releases_lock_when_inner_already_closed() throws Exception {
        var lock = new InProcessKeyedLockProvider();
        var key = uniqueKey();

        var outer = lock.acquire(key, FIVE_MIN);
        var inner = lock.acquire(key, FIVE_MIN);
        inner.close();
        outer.close();

        assertThat(otherThreadCanAcquire(lock, key)).isTrue();
    }

    @Test
    void close_order_does_not_matter_outer_first() throws Exception {
        var lock = new InProcessKeyedLockProvider();
        var key = uniqueKey();

        var outer = lock.acquire(key, FIVE_MIN);
        var inner = lock.acquire(key, FIVE_MIN);

        outer.close();
        assertThat(inner.isHeld()).isTrue();
        // Lock still held — other threads blocked.
        assertThat(otherThreadCanAcquire(lock, key)).isFalse();

        inner.close();
        // Now released.
        assertThat(otherThreadCanAcquire(lock, key)).isTrue();
    }

    @Test
    void inner_max_hold_is_ignored_outer_governs_watchdog() throws Exception {
        var lock = new InProcessKeyedLockProvider();
        var key = uniqueKey();

        try (var outer = lock.acquire(key, FIVE_MIN);
                var inner = lock.acquire(key, Duration.ofMillis(50))) {
            // Inner specified a 50ms maxHold but it must be ignored — outer's 5min governs.
            Thread.sleep(200);
            assertThat(outer.isHeld()).isTrue();
            assertThat(inner.isHeld()).isTrue();
            assertThat(otherThreadCanAcquire(lock, key)).isFalse();
        }
    }

    @Test
    void child_thread_is_a_different_identity_and_blocks() throws Exception {
        var lock = new InProcessKeyedLockProvider();
        var key = uniqueKey();
        var childAcquired = new CountDownLatch(1);
        var childError = new AtomicReference<Throwable>();

        try (var outer = lock.acquire(key, FIVE_MIN)) {
            // A child VT trying to acquire the same key should NOT see the parent's hold —
            // reentrancy is per-thread, not per-call-stack.
            var child = Thread.ofVirtual().start(() -> {
                try {
                    var got = lock.tryAcquire(key, Duration.ZERO, FIVE_MIN);
                    if (got.isPresent()) {
                        childError.set(new AssertionError("child should have been blocked"));
                        got.get().close();
                    }
                } catch (Throwable t) {
                    childError.set(t);
                } finally {
                    childAcquired.countDown();
                }
            });

            assertThat(childAcquired.await(5, TimeUnit.SECONDS)).isTrue();
            child.join();
            assertThat(childError.get()).isNull();
            assertThat(outer.isHeld()).isTrue();
        }
    }

    @Test
    void reentrant_acquire_after_watchdog_fired_does_a_fresh_acquire() throws Exception {
        var lock = new InProcessKeyedLockProvider();
        var key = uniqueKey();

        // First hold with a tight maxHold.
        var first = lock.acquire(key, Duration.ofMillis(50));
        Thread.sleep(200); // let the watchdog fire
        assertThat(first.isHeld()).isFalse();

        // Same thread can now do a fresh acquire — no stale entry blocking us.
        try (var second = lock.acquire(key, FIVE_MIN)) {
            assertThat(second.isHeld()).isTrue();
        }

        // close on the auto-expired lease is a safe no-op.
        first.close();
    }

    @Test
    void try_acquire_reentry_succeeds_immediately_with_zero_wait() throws Exception {
        var lock = new InProcessKeyedLockProvider();
        var key = uniqueKey();

        try (var outer = lock.acquire(key, FIVE_MIN)) {
            var inner = lock.tryAcquire(key, Duration.ZERO, FIVE_MIN);
            assertThat(inner).isPresent();
            assertThat(inner.get().isHeld()).isTrue();
            inner.get().close();
            assertThat(outer.isHeld()).isTrue();
        }
    }

    @Test
    void deeply_nested_reentries_are_supported() throws Exception {
        var lock = new InProcessKeyedLockProvider();
        var key = uniqueKey();

        var l1 = lock.acquire(key, FIVE_MIN);
        var l2 = lock.acquire(key, FIVE_MIN);
        var l3 = lock.acquire(key, FIVE_MIN);
        var l4 = lock.acquire(key, FIVE_MIN);

        assertThat(l1.isHeld()).isTrue();
        assertThat(l4.isHeld()).isTrue();
        assertThat(otherThreadCanAcquire(lock, key)).isFalse();

        l4.close();
        l3.close();
        l2.close();
        assertThat(otherThreadCanAcquire(lock, key)).isFalse();

        l1.close();
        assertThat(otherThreadCanAcquire(lock, key)).isTrue();
    }

    @Test
    void close_lease_is_idempotent_under_reentry() throws Exception {
        var lock = new InProcessKeyedLockProvider();
        var key = uniqueKey();

        var outer = lock.acquire(key, FIVE_MIN);
        var inner = lock.acquire(key, FIVE_MIN);

        // Closing inner multiple times must only decrement the counter once.
        inner.close();
        inner.close();
        inner.close();
        assertThat(outer.isHeld()).isTrue();
        assertThat(otherThreadCanAcquire(lock, key)).isFalse();

        outer.close();
        assertThat(otherThreadCanAcquire(lock, key)).isTrue();
    }

    /**
     * Runs {@code tryAcquire(key, ZERO, FIVE_MIN)} on a fresh virtual thread and returns whether
     * it succeeded. Used by reentrancy tests to assert mutual-exclusion against another caller
     * — same-thread checks would always succeed via reentry and tell us nothing.
     */
    private static boolean otherThreadCanAcquire(KeyedLockProvider lock, String key) throws Exception {
        var got = new java.util.concurrent.atomic.AtomicBoolean(false);
        var error = new AtomicReference<Throwable>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var lease = lock.tryAcquire(key, Duration.ZERO, FIVE_MIN);
                if (lease.isPresent()) {
                    got.set(true);
                    lease.get().close();
                }
            } catch (Throwable t) {
                error.set(t);
            }
        });
        thread.join();
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
        return got.get();
    }

    private static String uniqueKey() {
        return "test-key-" + UUID.randomUUID();
    }
}
