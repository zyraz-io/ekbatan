package io.ekbatan.test.concurrent_mariadb;

import static io.ekbatan.core.concurrent.MariaDBKeyedLockProvider.Builder.mariaDBKeyedLockProvider;
import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.persistence.ConnectionProvider.hikariConnectionProvider;
import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.core.concurrent.KeyedLockProvider;
import io.ekbatan.core.persistence.ConnectionProvider;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
            // WHEN
            var second = lock.tryAcquire(key, Duration.ZERO, FIVE_MIN);

            // THEN
            assertThat(first.isHeld()).isTrue();
            assertThat(second).isEmpty();
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
    void try_acquire_zero_wait_should_return_empty_when_held() throws Exception {
        // GIVEN
        var key = uniqueKey();
        try (var ignored = lock.acquire(key, FIVE_MIN)) {
            // WHEN
            var second = lock.tryAcquire(key, Duration.ZERO, FIVE_MIN);

            // THEN
            assertThat(second).isEmpty();
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
        // GIVEN
        var key = uniqueKey();
        try (var ignored = lock.acquire(key, FIVE_MIN)) {
            // WHEN — wait only 200ms while the holder is held for FIVE_MIN
            var start = System.nanoTime();
            var lease = lock.tryAcquire(key, Duration.ofMillis(200), FIVE_MIN);
            var elapsed = Duration.ofNanos(System.nanoTime() - start);

            // THEN — empty, and we returned in well under the holder's hold time (proving
            // GET_LOCK respected the timeout rather than blocking indefinitely)
            assertThat(lease).isEmpty();
            assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
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
        // GIVEN
        var key = uniqueKey();
        try (var ignored = lock.acquire(key, FIVE_MIN)) {
            // WHEN — half-second wait while the lock is held
            var start = System.nanoTime();
            var lease = lock.tryAcquire(key, Duration.ofMillis(500), FIVE_MIN);
            var elapsed = Duration.ofNanos(System.nanoTime() - start);

            // THEN
            assertThat(lease).isEmpty();
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

    private static String uniqueKey() {
        return "test-key-" + UUID.randomUUID();
    }
}
