package io.ekbatan.test.keyed_lock_provider;

import static io.ekbatan.keyedlock.redis.RedisKeyedLockProvider.Builder.redisKeyedLockProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.redis.testcontainers.RedisContainer;
import io.ekbatan.core.concurrent.KeyedLockProvider;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisKeyedLockProviderIntegrationTest {

    @Container
    private static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:6.2.6"));

    private static final Duration FIVE_MIN = Duration.ofMinutes(5);

    private static RedissonClient redisson;
    private static KeyedLockProvider lock;

    @BeforeAll
    static void setUp() {
        var config = new Config();
        config.useSingleServer().setAddress(REDIS.getRedisURI());
        redisson = Redisson.create(config);
        lock = redisKeyedLockProvider().redissonClient(redisson).build();
    }

    @AfterAll
    static void tearDown() {
        redisson.shutdown();
    }

    // ----- Input validation -----

    @Test
    void acquire_should_reject_null_key() {
        assertThatThrownBy(() -> lock.acquire(null, FIVE_MIN))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("key");
    }

    @Test
    void acquire_should_reject_zero_max_hold() {
        assertThatThrownBy(() -> lock.acquire("k", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxHold");
    }

    @Test
    void try_acquire_should_reject_negative_max_wait() {
        assertThatThrownBy(() -> lock.tryAcquire("k", Duration.ofMillis(-1), FIVE_MIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxWait");
    }

    @Test
    void builder_should_reject_missing_redisson_client() {
        assertThatThrownBy(() -> redisKeyedLockProvider().build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("redissonClient");
    }

    @Test
    void builder_should_reject_blank_namespace() {
        assertThatThrownBy(() -> redisKeyedLockProvider().namespace("")).isInstanceOf(IllegalArgumentException.class);
    }

    // ----- Mutual exclusion semantics -----

    @Test
    void acquire_should_provide_mutual_exclusion_for_same_key() throws Exception {
        var key = uniqueKey();

        try (var first = lock.acquire(key, FIVE_MIN)) {
            assertThat(first.isHeld()).isTrue();
            assertThat(otherThreadCanAcquire(key)).isFalse();
        }

        try (var third = lock.acquire(key, FIVE_MIN)) {
            assertThat(third.isHeld()).isTrue();
        }
    }

    @Test
    void acquire_should_not_block_different_keys() throws Exception {
        var key1 = uniqueKey();
        var key2 = uniqueKey();

        try (var l1 = lock.acquire(key1, FIVE_MIN);
                var l2 = lock.acquire(key2, FIVE_MIN)) {
            assertThat(l1.isHeld()).isTrue();
            assertThat(l2.isHeld()).isTrue();
        }
    }

    // ----- tryAcquire fast-path (zero wait) -----

    @Test
    void try_acquire_zero_wait_should_succeed_when_free() throws Exception {
        var key = uniqueKey();

        var lease = lock.tryAcquire(key, Duration.ZERO, FIVE_MIN);

        assertThat(lease).isPresent();
        assertThat(lease.get().isHeld()).isTrue();
        lease.get().close();
    }

    @Test
    void try_acquire_zero_wait_should_return_empty_when_held_by_another_thread() throws Exception {
        var key = uniqueKey();
        try (var ignored = lock.acquire(key, FIVE_MIN)) {
            assertThat(otherThreadCanAcquire(key)).isFalse();
        }
    }

    // ----- tryAcquire bounded wait -----

    @Test
    void try_acquire_with_max_wait_should_succeed_when_holder_releases_in_time() throws Exception {
        var key = uniqueKey();
        var holder = lock.acquire(key, FIVE_MIN);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
                holder.close();
            } catch (InterruptedException ignored) {
            }
        });

        var elapsedHolder = new AtomicReference<Duration>();
        var leaseHolder = new AtomicReference<Optional<KeyedLockProvider.Lease>>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var start = System.nanoTime();
                var lease = lock.tryAcquire(key, Duration.ofSeconds(5), FIVE_MIN);
                elapsedHolder.set(Duration.ofNanos(System.nanoTime() - start));
                leaseHolder.set(lease);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.join();

        assertThat(leaseHolder.get()).isPresent();
        assertThat(elapsedHolder.get()).isLessThan(Duration.ofSeconds(2));
        leaseHolder.get().get().close();
    }

    @Test
    void try_acquire_with_max_wait_should_return_empty_when_holder_does_not_release() throws Exception {
        var key = uniqueKey();
        try (var ignored = lock.acquire(key, FIVE_MIN)) {
            var elapsedHolder = new AtomicReference<Duration>();
            var leaseHolder = new AtomicReference<Optional<KeyedLockProvider.Lease>>();
            var thread = Thread.ofVirtual().start(() -> {
                try {
                    var start = System.nanoTime();
                    var lease = lock.tryAcquire(key, Duration.ofMillis(200), FIVE_MIN);
                    elapsedHolder.set(Duration.ofNanos(System.nanoTime() - start));
                    leaseHolder.set(lease);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            thread.join();

            assertThat(leaseHolder.get()).isEmpty();
            assertThat(elapsedHolder.get()).isLessThan(Duration.ofSeconds(5));
        }
    }

    // ----- maxHold safety net -----

    @Test
    void max_hold_should_auto_release_the_lock() throws Exception {
        var key = uniqueKey();
        var lease = lock.acquire(key, Duration.ofMillis(200));

        Thread.sleep(800);

        assertThat(lease.isHeld()).isFalse();
        try (var next = lock.acquire(key, FIVE_MIN)) {
            assertThat(next.isHeld()).isTrue();
        }

        // close after auto-expire is safe (idempotent)
        lease.close();
    }

    @Test
    void close_should_release_redis_with_owner_checked_unlock() throws Exception {
        var unlockCalls = new AtomicInteger();
        var forceUnlockCalls = new AtomicInteger();
        var lock = redisKeyedLockProvider()
                .redissonClient(fakeRedisson(fakeLock(unlockCalls, forceUnlockCalls)))
                .build();

        var lease = lock.acquire(uniqueKey(), FIVE_MIN);
        lease.close();

        assertThat(unlockCalls).hasValue(1);
        assertThat(forceUnlockCalls).hasValue(0);
    }

    @Test
    void max_hold_watchdog_should_not_send_stale_redis_unlock() throws Exception {
        var unlockCalls = new AtomicInteger();
        var forceUnlockCalls = new AtomicInteger();
        var lock = redisKeyedLockProvider()
                .redissonClient(fakeRedisson(fakeLock(unlockCalls, forceUnlockCalls)))
                .build();

        var lease = lock.acquire(uniqueKey(), Duration.ofMillis(50));

        Thread.sleep(250);

        assertThat(lease.isHeld()).isFalse();
        assertThat(unlockCalls).hasValue(0);
        assertThat(forceUnlockCalls).hasValue(0);

        lease.close();
        assertThat(unlockCalls).hasValue(0);
        assertThat(forceUnlockCalls).hasValue(0);
    }

    // ----- Correctness under contention -----

    @Test
    void concurrent_threads_should_serialize_increments_under_same_key() throws Exception {
        var key = uniqueKey();
        var threads = 8;
        var iterations = 25;
        var counter = new AtomicInteger(0);
        var done = new CountDownLatch(threads);

        for (var i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    for (var j = 0; j < iterations; j++) {
                        try (var ignored = lock.acquire(key, FIVE_MIN)) {
                            counter.set(counter.get() + 1);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(counter.get()).isEqualTo(threads * iterations);
    }

    // ----- Reentrancy -----

    @Test
    void reentry_same_thread_should_not_block() throws Exception {
        var key = uniqueKey();

        try (var outer = lock.acquire(key, FIVE_MIN);
                var inner = lock.acquire(key, FIVE_MIN)) {
            assertThat(outer.isHeld()).isTrue();
            assertThat(inner.isHeld()).isTrue();
        }

        try (var fresh = lock.acquire(key, FIVE_MIN)) {
            assertThat(fresh.isHeld()).isTrue();
        }
    }

    @Test
    void reentry_inner_close_should_not_release_underlying_lock() throws Exception {
        var key = uniqueKey();

        try (var outer = lock.acquire(key, FIVE_MIN)) {
            var inner = lock.acquire(key, FIVE_MIN);
            inner.close();

            assertThat(outer.isHeld()).isTrue();
            assertThat(otherThreadCanAcquire(key)).isFalse();
        }
    }

    @Test
    void reentry_outer_close_first_should_keep_lock_held_until_inner_closes() throws Exception {
        var key = uniqueKey();

        var outer = lock.acquire(key, FIVE_MIN);
        var inner = lock.acquire(key, FIVE_MIN);

        outer.close();
        assertThat(inner.isHeld()).isTrue();
        assertThat(otherThreadCanAcquire(key)).isFalse();

        inner.close();
        assertThat(otherThreadCanAcquire(key)).isTrue();
    }

    @Test
    void reentry_inner_max_hold_should_be_ignored() throws Exception {
        var key = uniqueKey();

        try (var outer = lock.acquire(key, FIVE_MIN);
                var inner = lock.acquire(key, Duration.ofMillis(50))) {
            // Inner specified a 50ms maxHold but it must be ignored - outer's 5min governs.
            // (If Redisson's last-call-wins were leaking through, the inner would shorten the
            // Redis TTL and another thread would be able to grab the key after 50ms.)
            Thread.sleep(200);
            assertThat(outer.isHeld()).isTrue();
            assertThat(inner.isHeld()).isTrue();
            assertThat(otherThreadCanAcquire(key)).isFalse();
        }
    }

    @Test
    void reentry_child_thread_should_be_a_different_identity_and_block() throws Exception {
        var key = uniqueKey();

        try (var outer = lock.acquire(key, FIVE_MIN)) {
            assertThat(otherThreadCanAcquire(key)).isFalse();
            assertThat(outer.isHeld()).isTrue();
        }
    }

    @Test
    void reentry_try_acquire_should_succeed_immediately_with_zero_wait() throws Exception {
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
    void reentry_inner_close_should_be_idempotent() throws Exception {
        var key = uniqueKey();

        var outer = lock.acquire(key, FIVE_MIN);
        var inner = lock.acquire(key, FIVE_MIN);

        inner.close();
        inner.close();
        inner.close();
        assertThat(outer.isHeld()).isTrue();
        assertThat(otherThreadCanAcquire(key)).isFalse();

        outer.close();
        assertThat(otherThreadCanAcquire(key)).isTrue();
    }

    // ----- Helpers -----

    private static boolean otherThreadCanAcquire(String key) throws Exception {
        var got = new AtomicBoolean(false);
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

    private static RedissonClient fakeRedisson(RLock lock) {
        return (RedissonClient) Proxy.newProxyInstance(
                RedisKeyedLockProviderIntegrationTest.class.getClassLoader(),
                new Class<?>[] {RedissonClient.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLock" -> lock;
                    case "toString" -> "fake-redisson-client";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.toString());
                });
    }

    // ----- Lease bounds: a positive maxHold must never become a non-expiring lease -----

    @Test
    void sub_millisecond_max_hold_should_still_produce_an_expiring_lease() throws Exception {
        // Duration.toMillis() truncates, so a sub-millisecond hold used to reach Redisson as
        // leaseTime=0 - which Redisson reads as "no lease" and answers by starting its renewal
        // watchdog, keeping the key alive for the life of the JVM. The lease must stay bounded.
        var key = uniqueKey();
        var lease = lock.acquire(key, Duration.ofNanos(500_000));

        var ttl = redisson.getLock("ekbatan-lock:" + key).remainTimeToLive();

        // -2 = key already gone, -1 = no expiry set (the defect), otherwise remaining ms. Redisson's
        // watchdog lease is 30s by default, so anything in that region means the bug is back.
        assertThat(ttl).isNotEqualTo(-1L);
        assertThat(ttl).isLessThan(1_000L);

        lease.close();
    }

    @Test
    void sub_millisecond_max_hold_should_not_block_a_later_acquirer() throws Exception {
        // The behavioural consequence of the above, and the one a user would actually hit: with a
        // renewing lease the key is never released - not by the framework watchdog either, which
        // deliberately skips the Redis unlock and relies on the lease expiring.
        var key = uniqueKey();
        var abandoned = lock.acquire(key, Duration.ofNanos(500_000));

        // From ANOTHER thread on purpose. Redisson's RLock is reentrant per thread, so a second
        // acquire on this thread would succeed through reentrancy even while the key is genuinely
        // held - it would pass whether or not the lease expires, and prove nothing.
        var acquiredElsewhere = CompletableFuture.supplyAsync(() -> {
            try (var next =
                    lock.tryAcquire(key, Duration.ofSeconds(5), FIVE_MIN).orElseThrow()) {
                return next.isHeld();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        // Never closed on purpose: expiry alone must free the key.
        assertThat(acquiredElsewhere.get(10, TimeUnit.SECONDS)).isTrue();

        abandoned.close();
    }

    // ----- Release failures must never escape close() -----

    @Test
    void close_should_not_throw_when_the_backend_release_fails() throws Exception {
        // Redis unreachable during release. Nothing can unlock a key on a server we cannot reach,
        // so the lease's TTL is the only remedy - but close() must not explode in the caller's
        // try-with-resources, where it would mask whatever the block was really doing.
        var lock = redisKeyedLockProvider()
                .redissonClient(fakeRedisson(
                        failingUnlockLock(new CompletionException(new IllegalStateException("Redis unreachable")))))
                .build();

        var lease = lock.acquire(uniqueKey(), FIVE_MIN);

        assertThatCode(lease::close).doesNotThrowAnyException();
    }

    @Test
    void close_should_not_throw_when_the_owner_check_fails() throws Exception {
        // The benign case: the lease's TTL expired before we got here, so Redisson's owner check
        // fails. Distinguished from the case above only by the cause, which is why the fix unwraps.
        var lock = redisKeyedLockProvider()
                .redissonClient(fakeRedisson(
                        failingUnlockLock(new CompletionException(new IllegalMonitorStateException("not held")))))
                .build();

        var lease = lock.acquire(uniqueKey(), FIVE_MIN);

        assertThatCode(lease::close).doesNotThrowAnyException();
    }

    @Test
    void close_should_not_throw_when_unlock_fails_without_a_completion_wrapper() throws Exception {
        // Guards the unwrap itself: not every failure arrives wrapped in a CompletionException, and
        // narrowing the catch to CompletionException would let this one escape close().
        var lock = redisKeyedLockProvider()
                .redissonClient(fakeRedisson(failingUnlockLock(new IllegalStateException("raw failure"))))
                .build();

        var lease = lock.acquire(uniqueKey(), FIVE_MIN);

        assertThatCode(lease::close).doesNotThrowAnyException();
    }

    @Test
    void a_failed_release_should_not_wedge_the_provider_for_that_key() throws Exception {
        // After a release failure the local bookkeeping must still be clean, so the same thread can
        // take the key again rather than being blocked by its own stale entry.
        var key = uniqueKey();
        var lock = redisKeyedLockProvider()
                .redissonClient(fakeRedisson(
                        failingUnlockLock(new CompletionException(new IllegalStateException("Redis unreachable")))))
                .build();

        lock.acquire(key, FIVE_MIN).close();

        try (var again = lock.acquire(key, FIVE_MIN)) {
            assertThat(again.isHeld()).isTrue();
        }
    }

    /** An {@link RLock} whose {@code unlockAsync} fails with {@code failure}. */
    private static RLock failingUnlockLock(RuntimeException failure) {
        return (RLock) Proxy.newProxyInstance(
                RedisKeyedLockProviderIntegrationTest.class.getClassLoader(),
                new Class<?>[] {RLock.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "lockInterruptibly" -> null;
                    case "tryLock" -> true;
                    case "unlockAsync" -> failedFuture(failure);
                    case "forceUnlock" -> true;
                    case "getName" -> "failing-lock";
                    case "isLocked", "isHeldByThread", "isHeldByCurrentThread" -> true;
                    case "getHoldCount" -> 1;
                    case "remainTimeToLive" -> 1L;
                    case "toString" -> "failing-rlock";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.toString());
                });
    }

    /** An {@link RFuture} that fails on {@code join()}, the way a real Redisson failure surfaces. */
    @SuppressWarnings("unchecked")
    private static <T> RFuture<T> failedFuture(RuntimeException failure) {
        return (RFuture<T>) Proxy.newProxyInstance(
                RedisKeyedLockProviderIntegrationTest.class.getClassLoader(),
                new Class<?>[] {RFuture.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toCompletableFuture" -> {
                        var future = new CompletableFuture<T>();
                        future.completeExceptionally(failure);
                        yield future;
                    }
                    case "join" -> throw failure;
                    case "isDone" -> true;
                    case "isCancelled" -> false;
                    case "isSuccess" -> false;
                    case "cause" -> failure;
                    case "toString" -> "failed-rfuture";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.toString());
                });
    }

    private static RLock fakeLock(AtomicInteger unlockCalls, AtomicInteger forceUnlockCalls) {
        return (RLock) Proxy.newProxyInstance(
                RedisKeyedLockProviderIntegrationTest.class.getClassLoader(),
                new Class<?>[] {RLock.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "lockInterruptibly" -> null;
                    case "tryLock" -> true;
                    case "unlockAsync" -> {
                        unlockCalls.incrementAndGet();
                        yield completedFuture(null);
                    }
                    case "forceUnlock" -> {
                        forceUnlockCalls.incrementAndGet();
                        yield true;
                    }
                    case "getName" -> "fake-lock";
                    case "isLocked", "isHeldByThread", "isHeldByCurrentThread" -> true;
                    case "getHoldCount" -> 1;
                    case "remainTimeToLive" -> 1L;
                    case "toString" -> "fake-rlock";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.toString());
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> RFuture<T> completedFuture(T value) {
        var future = CompletableFuture.completedFuture(value);
        return (RFuture<T>) Proxy.newProxyInstance(
                RedisKeyedLockProviderIntegrationTest.class.getClassLoader(),
                new Class<?>[] {RFuture.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toCompletableFuture" -> future;
                    case "join" -> future.join();
                    case "get" -> args == null ? future.get() : future.get((long) args[0], (TimeUnit) args[1]);
                    case "isDone" -> future.isDone();
                    case "isCancelled" -> future.isCancelled();
                    case "cancel" -> future.cancel((boolean) args[0]);
                    case "isSuccess" -> future.isDone() && !future.isCompletedExceptionally();
                    case "cause" -> null;
                    case "getNow" -> future.getNow(null);
                    case "toString" -> "completed-rfuture";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.toString());
                });
    }

    private static String uniqueKey() {
        return "test-key-" + UUID.randomUUID();
    }
}
