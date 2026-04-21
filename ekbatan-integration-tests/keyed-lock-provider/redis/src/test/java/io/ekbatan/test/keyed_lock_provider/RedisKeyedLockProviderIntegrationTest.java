package io.ekbatan.test.keyed_lock_provider;

import static io.ekbatan.keyedlock.redis.RedisKeyedLockProvider.Builder.redisKeyedLockProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.redis.testcontainers.RedisContainer;
import io.ekbatan.core.concurrent.KeyedLockProvider;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
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
            // Inner specified a 50ms maxHold but it must be ignored — outer's 5min governs.
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

    private static String uniqueKey() {
        return "test-key-" + UUID.randomUUID();
    }
}
