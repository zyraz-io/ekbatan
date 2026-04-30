package io.ekbatan.core.concurrent;

import static io.ekbatan.core.concurrent.PostgresKeyedLockProvider.Builder.postgresKeyedLockProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ekbatan.core.persistence.ConnectionProvider;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;

// See ActionExecutorTest for the full rationale: Mockito + GraalVM native is blocked
// upstream on JDK 25 (oracle/graal#12723) and on JDK 26 EA. The real provider contract
// is exercised against a live Postgres in
// ekbatan-integration-tests:keyed-lock-provider:pg (Testcontainers), so native
// validation of the locking semantics is preserved.
@DisabledInNativeImage
class PostgresKeyedLockProviderTest {

    private static final Duration ONE_HOUR = Duration.ofHours(1);
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    // ----- Input validation -----

    @Test
    void acquire_should_reject_null_key() {
        var lock = newLock();
        assertThatThrownBy(() -> lock.acquire(null, ONE_HOUR))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("key");
    }

    @Test
    void acquire_should_reject_null_max_hold() {
        var lock = newLock();
        assertThatThrownBy(() -> lock.acquire("k", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("maxHold");
    }

    @Test
    void acquire_should_reject_zero_max_hold() {
        var lock = newLock();
        assertThatThrownBy(() -> lock.acquire("k", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxHold");
    }

    @Test
    void acquire_should_reject_negative_max_hold() {
        var lock = newLock();
        assertThatThrownBy(() -> lock.acquire("k", Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxHold");
    }

    @Test
    void try_acquire_should_reject_null_key() {
        var lock = newLock();
        assertThatThrownBy(() -> lock.tryAcquire(null, ONE_SECOND, ONE_HOUR))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("key");
    }

    @Test
    void try_acquire_should_reject_null_max_wait() {
        var lock = newLock();
        assertThatThrownBy(() -> lock.tryAcquire("k", null, ONE_HOUR))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("maxWait");
    }

    @Test
    void try_acquire_should_reject_negative_max_wait() {
        var lock = newLock();
        assertThatThrownBy(() -> lock.tryAcquire("k", Duration.ofMillis(-1), ONE_HOUR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxWait");
    }

    @Test
    void try_acquire_should_reject_zero_max_hold() {
        var lock = newLock();
        assertThatThrownBy(() -> lock.tryAcquire("k", ONE_SECOND, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxHold");
    }

    @Test
    void builder_should_reject_missing_connection_provider() {
        assertThatThrownBy(() -> postgresKeyedLockProvider().build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("connectionProvider");
    }

    // ----- acquire() lifecycle -----

    @Test
    void acquire_success_should_return_held_lease_and_keep_connection() throws Exception {
        var jdbc = new JdbcMocks();
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var lease = lock.acquire("k", ONE_HOUR);

        assertThat(lease.isHeld()).isTrue();
        verify(jdbc.lockStmt).execute();
        verify(provider, never()).release(any());
        verify(provider, never()).evict(any());

        lease.close();
    }

    @Test
    void acquire_sql_failure_should_release_connection_and_throw() throws Exception {
        var jdbc = new JdbcMocks();
        doThrow(new SQLException("boom")).when(jdbc.lockStmt).execute();
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        assertThatThrownBy(() -> lock.acquire("k", ONE_HOUR))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to acquire advisory lock for key k")
                .hasCauseInstanceOf(SQLException.class);

        verify(provider).release(jdbc.connection);
        verify(provider, never()).evict(any());
    }

    @Test
    void close_lease_should_unlock_and_release_connection() throws Exception {
        var jdbc = new JdbcMocks();
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var lease = lock.acquire("k", ONE_HOUR);
        lease.close();

        verify(jdbc.unlockStmt).execute();
        verify(provider).release(jdbc.connection);
        assertThat(lease.isHeld()).isFalse();
    }

    @Test
    void close_lease_should_be_idempotent() throws Exception {
        var jdbc = new JdbcMocks();
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var lease = lock.acquire("k", ONE_HOUR);
        lease.close();
        lease.close();
        lease.close();

        verify(jdbc.unlockStmt, times(1)).execute();
        verify(provider, times(1)).release(jdbc.connection);
    }

    @Test
    void close_lease_should_evict_connection_when_unlock_fails() throws Exception {
        var jdbc = new JdbcMocks();
        doThrow(new SQLException("unlock failed")).when(jdbc.unlockStmt).execute();
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var lease = lock.acquire("k", ONE_HOUR);
        lease.close();

        verify(provider).evict(jdbc.connection);
        verify(provider, never()).release(any());
    }

    // ----- tryAcquire(zero wait) -----

    @Test
    void try_acquire_zero_wait_acquired_should_return_held_lease() throws Exception {
        var jdbc = new JdbcMocks();
        when(jdbc.tryLockResult.next()).thenReturn(true);
        when(jdbc.tryLockResult.getBoolean(1)).thenReturn(true);
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var leaseOpt = lock.tryAcquire("k", Duration.ZERO, ONE_HOUR);

        assertThat(leaseOpt).isPresent();
        assertThat(leaseOpt.get().isHeld()).isTrue();
        verify(jdbc.tryLockStmt).executeQuery();
        verify(provider, never()).release(any());
        leaseOpt.get().close();
    }

    @Test
    void try_acquire_zero_wait_not_acquired_should_release_connection() throws Exception {
        var jdbc = new JdbcMocks();
        when(jdbc.tryLockResult.next()).thenReturn(true);
        when(jdbc.tryLockResult.getBoolean(1)).thenReturn(false);
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var leaseOpt = lock.tryAcquire("k", Duration.ZERO, ONE_HOUR);

        assertThat(leaseOpt).isEmpty();
        verify(provider).release(jdbc.connection);
        verify(provider, never()).evict(any());
    }

    @Test
    void try_acquire_zero_wait_sql_failure_should_throw_and_release() throws Exception {
        var jdbc = new JdbcMocks();
        doThrow(new SQLException("boom")).when(jdbc.tryLockStmt).executeQuery();
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        assertThatThrownBy(() -> lock.tryAcquire("k", Duration.ZERO, ONE_HOUR))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(SQLException.class);

        verify(provider).release(jdbc.connection);
        verify(provider, never()).evict(any());
    }

    // ----- tryAcquire(positive wait) -----

    @Test
    void try_acquire_positive_wait_acquired_clean_should_return_held_lease() throws Exception {
        var jdbc = new JdbcMocks();
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var leaseOpt = lock.tryAcquire("k", ONE_SECOND, ONE_HOUR);

        assertThat(leaseOpt).isPresent();
        assertThat(leaseOpt.get().isHeld()).isTrue();
        verify(jdbc.setStmt).execute("SET lock_timeout = 1000");
        verify(jdbc.lockStmt).execute();
        verify(jdbc.resetStmt).execute("SET lock_timeout = 0");
        verify(provider, never()).release(any());
        verify(provider, never()).evict(any());

        leaseOpt.get().close();
    }

    @Test
    void try_acquire_positive_wait_lock_not_available_should_return_empty() throws Exception {
        var jdbc = new JdbcMocks();
        doThrow(new SQLException("lock_timeout fired", "55P03"))
                .when(jdbc.lockStmt)
                .execute();
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var leaseOpt = lock.tryAcquire("k", ONE_SECOND, ONE_HOUR);

        assertThat(leaseOpt).isEmpty();
        verify(jdbc.resetStmt).execute("SET lock_timeout = 0");
        verify(provider).release(jdbc.connection);
        verify(provider, never()).evict(any());
    }

    @Test
    void try_acquire_positive_wait_other_sql_error_should_throw() throws Exception {
        var jdbc = new JdbcMocks();
        doThrow(new SQLException("connection lost", "08006"))
                .when(jdbc.lockStmt)
                .execute();
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        assertThatThrownBy(() -> lock.tryAcquire("k", ONE_SECOND, ONE_HOUR))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(SQLException.class);

        verify(jdbc.resetStmt).execute("SET lock_timeout = 0");
        verify(provider).release(jdbc.connection);
        verify(provider, never()).evict(any());
    }

    @Test
    void try_acquire_set_lock_timeout_failure_should_release_and_throw() throws Exception {
        var jdbc = new JdbcMocks();
        doThrow(new SQLException("set failed")).when(jdbc.setStmt).execute(anyString());
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        assertThatThrownBy(() -> lock.tryAcquire("k", ONE_SECOND, ONE_HOUR))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(SQLException.class);

        verify(provider).release(jdbc.connection);
        verify(provider, never()).evict(any());
        verify(jdbc.lockStmt, never()).execute();
    }

    @Test
    void try_acquire_acquired_but_reset_fails_should_return_lease_that_evicts_on_close() throws Exception {
        var jdbc = new JdbcMocks();
        doThrow(new SQLException("reset failed")).when(jdbc.resetStmt).execute(anyString());
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var leaseOpt = lock.tryAcquire("k", ONE_SECOND, ONE_HOUR);

        assertThat(leaseOpt).isPresent();
        verify(provider, never()).evict(any());
        verify(provider, never()).release(any());

        leaseOpt.get().close();

        verify(jdbc.unlockStmt).execute();
        verify(provider).evict(jdbc.connection);
        verify(provider, never()).release(any());
    }

    @Test
    void try_acquire_lock_failed_and_reset_fails_should_evict_immediately() throws Exception {
        var jdbc = new JdbcMocks();
        doThrow(new SQLException("conn dead", "08006")).when(jdbc.lockStmt).execute();
        doThrow(new SQLException("reset failed")).when(jdbc.resetStmt).execute(anyString());
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        assertThatThrownBy(() -> lock.tryAcquire("k", ONE_SECOND, ONE_HOUR)).isInstanceOf(RuntimeException.class);

        verify(provider).evict(jdbc.connection);
        verify(provider, never()).release(any());
    }

    @Test
    void try_acquire_lock_not_available_with_reset_failure_should_return_empty_and_evict() throws Exception {
        var jdbc = new JdbcMocks();
        doThrow(new SQLException("lock_timeout fired", "55P03"))
                .when(jdbc.lockStmt)
                .execute();
        doThrow(new SQLException("reset failed")).when(jdbc.resetStmt).execute(anyString());
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var leaseOpt = lock.tryAcquire("k", ONE_SECOND, ONE_HOUR);

        assertThat(leaseOpt).isEmpty();
        verify(provider).evict(jdbc.connection);
        verify(provider, never()).release(any());
    }

    @Test
    void try_acquire_sub_millisecond_wait_should_clamp_to_one_millisecond() throws Exception {
        var jdbc = new JdbcMocks();
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        // Duration.ofNanos(500) = 0ms after toMillis() — Math.max(1L, ...) clamps to 1
        var leaseOpt = lock.tryAcquire("k", Duration.ofNanos(500), ONE_HOUR);

        assertThat(leaseOpt).isPresent();
        verify(jdbc.setStmt).execute("SET lock_timeout = 1");
        leaseOpt.get().close();
    }

    // ----- maxHold expiration -----

    @Test
    void max_hold_expiration_should_release_lock_automatically() throws Exception {
        var jdbc = new JdbcMocks();
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var lease = lock.acquire("k", Duration.ofMillis(50));

        verify(provider, timeout(2000)).release(jdbc.connection);
        verify(jdbc.unlockStmt).execute();
        assertThat(lease.isHeld()).isFalse();
    }

    @Test
    void close_after_expire_should_be_no_op() throws Exception {
        var jdbc = new JdbcMocks();
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var lease = lock.acquire("k", Duration.ofMillis(50));
        verify(provider, timeout(2000)).release(jdbc.connection);
        lease.close();

        verify(jdbc.unlockStmt, times(1)).execute();
        verify(provider, times(1)).release(jdbc.connection);
    }

    // ----- Helpers -----

    private static PostgresKeyedLockProvider newLock() {
        return newLock(mock(ConnectionProvider.class));
    }

    private static PostgresKeyedLockProvider newLock(ConnectionProvider provider) {
        return postgresKeyedLockProvider().connectionProvider(provider).build();
    }

    private static ConnectionProvider newProvider(Connection conn) {
        var p = mock(ConnectionProvider.class);
        when(p.acquire()).thenReturn(conn);
        return p;
    }

    /**
     * Wires a mock {@link Connection} so the SQL statements used by {@link PostgresKeyedLockProvider}
     * each return their own mock — letting tests verify or stub them individually.
     */
    private static final class JdbcMocks {
        final Connection connection = mock(Connection.class);
        final PreparedStatement lockStmt = mock(PreparedStatement.class);
        final PreparedStatement tryLockStmt = mock(PreparedStatement.class);
        final PreparedStatement unlockStmt = mock(PreparedStatement.class);
        final Statement setStmt = mock(Statement.class);
        final Statement resetStmt = mock(Statement.class);
        final ResultSet tryLockResult = mock(ResultSet.class);

        JdbcMocks() throws SQLException {
            when(connection.prepareStatement("SELECT pg_advisory_lock(?)")).thenReturn(lockStmt);
            when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(tryLockStmt);
            when(connection.prepareStatement("SELECT pg_advisory_unlock(?)")).thenReturn(unlockStmt);
            when(connection.createStatement()).thenReturn(setStmt, resetStmt);
            when(tryLockStmt.executeQuery()).thenReturn(tryLockResult);
        }
    }
}
