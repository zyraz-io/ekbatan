package io.ekbatan.test.concurrent_mysql;

import static io.ekbatan.core.concurrent.MySQLKeyedLockProvider.Builder.mySQLKeyedLockProvider;
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
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class MySQLKeyedLockProviderIntegrationTest {

    @Container
    private static final MySQLContainer DB = new MySQLContainer("mysql:latest")
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
        lock = mySQLKeyedLockProvider().connectionProvider(provider).build();
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
     * MySQL has 1-second timeout granularity for {@code GET_LOCK}: a 0.5-second wait
     * against a held lock waits ~1 second, not ~500ms. This test pins that documented
     * behavior so we notice if MySQL ever fixes it (which would suggest the precision
     * caveat in the class Javadoc can be relaxed).
     */
    @Test
    void try_acquire_max_wait_should_round_up_to_whole_seconds() throws Exception {
        // GIVEN
        var key = uniqueKey();
        try (var ignored = lock.acquire(key, FIVE_MIN)) {
            // WHEN — request half a second
            var start = System.nanoTime();
            var lease = lock.tryAcquire(key, Duration.ofMillis(500), FIVE_MIN);
            var elapsed = Duration.ofNanos(System.nanoTime() - start);

            // THEN — MySQL waits ~1s, not ~500ms
            assertThat(lease).isEmpty();
            assertThat(elapsed)
                    .as("elapsed=%dms — MySQL rounds GET_LOCK timeout up to whole seconds", elapsed.toMillis())
                    .isBetween(Duration.ofMillis(900), Duration.ofMillis(1500));
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
