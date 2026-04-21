package io.ekbatan.test.keyed_lock_provider;

import static io.ekbatan.core.concurrent.MariaDBKeyedLockProvider.Builder.mariaDBKeyedLockProvider;
import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.persistence.ConnectionProvider.hikariConnectionProvider;
import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.core.concurrent.KeyedLockProvider;
import io.ekbatan.core.persistence.ConnectionProvider;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

@Testcontainers
class MariaDBKeyedLockProviderIntegrationTest {

    @Container
    private static final MariaDBContainer DB = new MariaDBContainer("mariadb:latest")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC");

    private static final Duration FIVE_MIN = Duration.ofMinutes(5);

    private static ConnectionProvider provider;
    private static KeyedLockProvider lock;

    @BeforeAll
    static void setUp() {
        var cfg = dataSourceConfig()
                .jdbcUrl(DB.getJdbcUrl())
                .username(DB.getUsername())
                .password(DB.getPassword())
                .maximumPoolSize(16)
                .build();
        provider = hikariConnectionProvider(cfg);
        lock = mariaDBKeyedLockProvider().connectionProvider(provider).build();
    }

    @AfterAll
    static void tearDown() {
        provider.getDataSource().close();
    }

    // ----- Mutual exclusion semantics -----

    @Test
    void acquire_should_provide_mutual_exclusion_for_same_key() throws Exception {
        // GIVEN
        var key = uniqueKey();

        try (var first = lock.acquire(key, FIVE_MIN)) {
            // WHEN — another thread tries to acquire the same key. (Same-thread acquires would
            // succeed via reentry, so the cross-thread check is what proves mutual exclusion.)
            assertThat(first.isHeld()).isTrue();
            assertThat(otherThreadCanAcquire(key)).isFalse();
        }

        // AND — once released, the lock is acquirable again
        try (var third = lock.acquire(key, FIVE_MIN)) {
            assertThat(third.isHeld()).isTrue();
        }
    }

    @Test
    void acquire_should_not_block_different_keys() throws Exception {
        // GIVEN
        var key1 = uniqueKey();
        var key2 = uniqueKey();

        // WHEN / THEN
        try (var l1 = lock.acquire(key1, FIVE_MIN);
                var l2 = lock.acquire(key2, FIVE_MIN)) {
            assertThat(l1.isHeld()).isTrue();
            assertThat(l2.isHeld()).isTrue();
        }
    }

    // ----- tryAcquire fast-path (zero wait) -----

    @Test
    void try_acquire_zero_wait_should_succeed_when_free() throws Exception {
        // GIVEN
        var key = uniqueKey();

        // WHEN
        var lease = lock.tryAcquire(key, Duration.ZERO, FIVE_MIN);

        // THEN
        assertThat(lease).isPresent();
        assertThat(lease.get().isHeld()).isTrue();
        lease.get().close();
    }

    @Test
    void try_acquire_zero_wait_should_return_empty_when_held_by_another_thread() throws Exception {
        // GIVEN
        var key = uniqueKey();
        try (var ignored = lock.acquire(key, FIVE_MIN)) {
            // WHEN / THEN — another thread sees the lock as taken
            assertThat(otherThreadCanAcquire(key)).isFalse();
        }
    }

    // ----- tryAcquire bounded wait -----

    @Test
    void try_acquire_with_max_wait_should_succeed_when_holder_releases_in_time() throws Exception {
        // GIVEN — first thread holds, releases after 200ms
        var key = uniqueKey();
        var holder = lock.acquire(key, FIVE_MIN);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
                holder.close();
            } catch (InterruptedException ignored) {
            }
        });

        // WHEN — wait up to 5s
        var start = System.nanoTime();
        var lease = lock.tryAcquire(key, Duration.ofSeconds(5), FIVE_MIN);
        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        // THEN
        assertThat(lease).isPresent();
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        lease.get().close();
    }

    @Test
    void try_acquire_with_max_wait_should_return_empty_when_holder_does_not_release() throws Exception {
        // GIVEN — main thread holds for the full FIVE_MIN
        var key = uniqueKey();
        try (var ignored = lock.acquire(key, FIVE_MIN)) {
            // WHEN — a different thread tries to acquire with a 200ms wait
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

            // THEN — empty, and the wait returned in well under the holder's hold time
            // (proving GET_LOCK respected the timeout rather than blocking indefinitely)
            assertThat(leaseHolder.get()).isEmpty();
            assertThat(elapsedHolder.get()).isLessThan(Duration.ofSeconds(5));
        }
    }

    /**
     * Verifies modern MariaDB (10.0.2+) honors fractional-second {@code GET_LOCK} timeouts.
     * Pre-modern versions rounded 500ms either to 0s (try-once → returns immediately) or to
     * 1s (waits a full second), so the elapsed time would be either much less than 200ms or
     * much greater than 900ms. Anything in between proves sub-second precision is in effect.
     */
    @Test
    void try_acquire_should_honor_sub_second_max_wait() throws Exception {
        // GIVEN — main thread holds the lock
        var key = uniqueKey();
        try (var ignored = lock.acquire(key, FIVE_MIN)) {
            // WHEN — a different thread does a half-second wait
            var elapsedHolder = new AtomicReference<Duration>();
            var leaseHolder = new AtomicReference<Optional<KeyedLockProvider.Lease>>();
            var thread = Thread.ofVirtual().start(() -> {
                try {
                    var start = System.nanoTime();
                    var lease = lock.tryAcquire(key, Duration.ofMillis(500), FIVE_MIN);
                    elapsedHolder.set(Duration.ofNanos(System.nanoTime() - start));
                    leaseHolder.set(lease);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            thread.join();

            // THEN
            var elapsed = elapsedHolder.get();
            assertThat(leaseHolder.get()).isEmpty();
            assertThat(elapsed)
                    .as(
                            "elapsed=%dms — sub-second precision means ≈500ms, not 0ms (try-once) or 1000ms (rounded up)",
                            elapsed.toMillis())
                    .isBetween(Duration.ofMillis(200), Duration.ofMillis(950));
        }
    }

    // ----- maxHold safety net -----

    @Test
    void max_hold_should_auto_release_the_lock() throws Exception {
        // GIVEN
        var key = uniqueKey();
        var lease = lock.acquire(key, Duration.ofMillis(200));

        // WHEN
        Thread.sleep(600);

        // THEN
        assertThat(lease.isHeld()).isFalse();

        // AND — another caller can now acquire the same key
        try (var next = lock.acquire(key, FIVE_MIN)) {
            assertThat(next.isHeld()).isTrue();
        }

        // AND — close after auto-expire is safe (idempotent)
        lease.close();
    }

    // ----- Connection lifecycle -----

    @Test
    void close_lease_should_return_connection_to_pool() throws Exception {
        // GIVEN
        var hikari = provider.getDataSource();
        var beforeActive = hikari.getHikariPoolMXBean().getActiveConnections();

        // WHEN
        for (var i = 0; i < 50; i++) {
            try (var ignored = lock.acquire(uniqueKey(), FIVE_MIN)) {
                // hold briefly
            }
        }

        // THEN
        assertThat(hikari.getHikariPoolMXBean().getActiveConnections()).isEqualTo(beforeActive);
    }

    // ----- Correctness under contention -----

    @Test
    void concurrent_threads_should_serialize_increments_under_same_key() throws Exception {
        // GIVEN
        var key = uniqueKey();
        var threads = 8;
        var iterations = 25;
        var counter = new AtomicInteger(0);
        var done = new CountDownLatch(threads);

        // WHEN
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

        // THEN
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(counter.get()).isEqualTo(threads * iterations);
    }

    // ----- Reentrancy -----

    @Test
    void reentry_same_thread_should_not_block_or_borrow_extra_connections() throws Exception {
        var key = uniqueKey();
        var hikari = provider.getDataSource();
        var beforeActive = hikari.getHikariPoolMXBean().getActiveConnections();

        try (var outer = lock.acquire(key, FIVE_MIN);
                var inner = lock.acquire(key, FIVE_MIN)) {
            assertThat(outer.isHeld()).isTrue();
            assertThat(inner.isHeld()).isTrue();
            assertThat(hikari.getHikariPoolMXBean().getActiveConnections() - beforeActive)
                    .isEqualTo(1);
        }

        assertThat(hikari.getHikariPoolMXBean().getActiveConnections()).isEqualTo(beforeActive);
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

    /**
     * Runs {@code tryAcquire(key, ZERO, FIVE_MIN)} on a fresh virtual thread and returns
     * whether it succeeded. Used by reentrancy tests to assert mutual-exclusion against
     * another caller — same-thread checks would always succeed via re-entry and tell us
     * nothing.
     */
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
