package io.ekbatan.core.concurrent;

import static io.ekbatan.core.concurrent.MySQLKeyedLockProvider.Builder.mySQLKeyedLockProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MySQLKeyedLockProviderTest {

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
        assertThatThrownBy(() -> mySQLKeyedLockProvider().build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("connectionProvider");
    }

    // ----- acquire() lifecycle -----

    @Test
    void acquire_success_should_return_held_lease_and_keep_connection() throws Exception {
        var jdbc = new JdbcMocks().getLockReturns(1);
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var lease = lock.acquire("k", ONE_HOUR);

        assertThat(lease.isHeld()).isTrue();
        verify(jdbc.getLockStmt).executeQuery();
        verify(provider, never()).release(any());
        verify(provider, never()).evict(any());

        lease.close();
    }

    @Test
    void acquire_sql_failure_should_release_connection_and_throw() throws Exception {
        var jdbc = new JdbcMocks();
        doThrow(new SQLException("boom")).when(jdbc.getLockStmt).executeQuery();
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        assertThatThrownBy(() -> lock.acquire("k", ONE_HOUR))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to acquire user lock for key k")
                .hasCauseInstanceOf(SQLException.class);

        verify(provider).release(jdbc.connection);
        verify(provider, never()).evict(any());
    }

    @Test
    void acquire_zero_response_should_release_and_throw() throws Exception {
        var jdbc = new JdbcMocks().getLockReturns(0);
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        assertThatThrownBy(() -> lock.acquire("k", ONE_HOUR))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("server-side disruption");

        verify(provider).release(jdbc.connection);
        verify(provider, never()).evict(any());
    }

    @Test
    void acquire_null_response_should_release_and_throw() throws Exception {
        var jdbc = new JdbcMocks().getLockReturnsNull();
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        assertThatThrownBy(() -> lock.acquire("k", ONE_HOUR))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("server-side disruption");

        verify(provider).release(jdbc.connection);
        verify(provider, never()).evict(any());
    }

    @Test
    void close_lease_should_release_lock_and_return_connection() throws Exception {
        var jdbc = new JdbcMocks().getLockReturns(1).releaseLockReturns(1);
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var lease = lock.acquire("k", ONE_HOUR);
        lease.close();

        verify(jdbc.releaseLockStmt).executeQuery();
        verify(provider).release(jdbc.connection);
        assertThat(lease.isHeld()).isFalse();
    }

    @Test
    void close_lease_should_be_idempotent() throws Exception {
        var jdbc = new JdbcMocks().getLockReturns(1).releaseLockReturns(1);
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var lease = lock.acquire("k", ONE_HOUR);
        lease.close();
        lease.close();
        lease.close();

        verify(jdbc.releaseLockStmt, times(1)).executeQuery();
        verify(provider, times(1)).release(jdbc.connection);
    }

    @Test
    void close_lease_should_evict_connection_when_release_lock_throws() throws Exception {
        var jdbc = new JdbcMocks().getLockReturns(1);
        doThrow(new SQLException("release failed")).when(jdbc.releaseLockStmt).executeQuery();
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var lease = lock.acquire("k", ONE_HOUR);
        lease.close();

        verify(provider).evict(jdbc.connection);
        verify(provider, never()).release(any());
    }

    @Test
    void close_lease_should_return_connection_when_release_lock_returns_zero() throws Exception {
        var jdbc = new JdbcMocks().getLockReturns(1).releaseLockReturns(0);
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var lease = lock.acquire("k", ONE_HOUR);
        lease.close();

        verify(provider).release(jdbc.connection);
        verify(provider, never()).evict(any());
    }

    @Test
    void close_lease_should_return_connection_when_release_lock_returns_null() throws Exception {
        var jdbc = new JdbcMocks().getLockReturns(1).releaseLockReturnsNull();
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var lease = lock.acquire("k", ONE_HOUR);
        lease.close();

        verify(provider).release(jdbc.connection);
        verify(provider, never()).evict(any());
    }

    // ----- tryAcquire (zero wait) -----

    @Test
    void try_acquire_zero_wait_acquired_should_return_held_lease() throws Exception {
        var jdbc = new JdbcMocks().getLockReturns(1);
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var leaseOpt = lock.tryAcquire("k", Duration.ZERO, ONE_HOUR);

        assertThat(leaseOpt).isPresent();
        assertThat(leaseOpt.get().isHeld()).isTrue();
        verify(jdbc.getLockStmt).setDouble(2, 0.0);
        verify(provider, never()).release(any());

        leaseOpt.get().close();
    }

    @Test
    void try_acquire_zero_wait_not_acquired_should_release_connection() throws Exception {
        var jdbc = new JdbcMocks().getLockReturns(0);
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var leaseOpt = lock.tryAcquire("k", Duration.ZERO, ONE_HOUR);

        assertThat(leaseOpt).isEmpty();
        verify(provider).release(jdbc.connection);
        verify(provider, never()).evict(any());
    }

    @Test
    void try_acquire_null_response_should_be_treated_as_not_acquired() throws Exception {
        var jdbc = new JdbcMocks().getLockReturnsNull();
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var leaseOpt = lock.tryAcquire("k", ONE_SECOND, ONE_HOUR);

        assertThat(leaseOpt).isEmpty();
        verify(provider).release(jdbc.connection);
        verify(provider, never()).evict(any());
    }

    @Test
    void try_acquire_sql_failure_should_throw_and_release() throws Exception {
        var jdbc = new JdbcMocks();
        doThrow(new SQLException("boom")).when(jdbc.getLockStmt).executeQuery();
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        assertThatThrownBy(() -> lock.tryAcquire("k", Duration.ZERO, ONE_HOUR))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(SQLException.class);

        verify(provider).release(jdbc.connection);
        verify(provider, never()).evict(any());
    }

    // ----- tryAcquire (positive wait) -----

    @Test
    void try_acquire_positive_wait_acquired_should_pass_fractional_seconds() throws Exception {
        var jdbc = new JdbcMocks().getLockReturns(1);
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var leaseOpt = lock.tryAcquire("k", Duration.ofMillis(2500), ONE_HOUR);

        assertThat(leaseOpt).isPresent();
        verify(jdbc.getLockStmt).setDouble(2, 2.5);

        leaseOpt.get().close();
    }

    @Test
    void try_acquire_sub_millisecond_wait_should_clamp_to_one_millisecond() throws Exception {
        var jdbc = new JdbcMocks().getLockReturns(1);
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var leaseOpt = lock.tryAcquire("k", Duration.ofNanos(500), ONE_HOUR);

        assertThat(leaseOpt).isPresent();
        verify(jdbc.getLockStmt).setDouble(2, 0.001);

        leaseOpt.get().close();
    }

    @Test
    void try_acquire_positive_wait_timed_out_should_return_empty() throws Exception {
        var jdbc = new JdbcMocks().getLockReturns(0);
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var leaseOpt = lock.tryAcquire("k", ONE_SECOND, ONE_HOUR);

        assertThat(leaseOpt).isEmpty();
        verify(provider).release(jdbc.connection);
        verify(provider, never()).evict(any());
    }

    // ----- acquire() passes -1 (wait forever) -----

    @Test
    void acquire_should_pass_negative_timeout_for_wait_forever() throws Exception {
        // MySQL still honors negative GET_LOCK timeout as "wait forever," so we use the
        // historical sentinel directly — no need for the very-large-finite workaround that
        // the MariaDB impl needs.
        var jdbc = new JdbcMocks().getLockReturns(1);
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var lease = lock.acquire("k", ONE_HOUR);

        verify(jdbc.getLockStmt).setDouble(2, -1.0);

        lease.close();
    }

    // ----- maxHold expiration -----

    @Test
    void max_hold_expiration_should_release_lock_automatically() throws Exception {
        var jdbc = new JdbcMocks().getLockReturns(1).releaseLockReturns(1);
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var lease = lock.acquire("k", Duration.ofMillis(50));

        verify(provider, timeout(2000)).release(jdbc.connection);
        verify(jdbc.releaseLockStmt).executeQuery();
        assertThat(lease.isHeld()).isFalse();
    }

    @Test
    void close_after_expire_should_be_no_op() throws Exception {
        var jdbc = new JdbcMocks().getLockReturns(1).releaseLockReturns(1);
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var lease = lock.acquire("k", Duration.ofMillis(50));
        verify(provider, timeout(2000)).release(jdbc.connection);
        lease.close();

        verify(jdbc.releaseLockStmt, times(1)).executeQuery();
        verify(provider, times(1)).release(jdbc.connection);
    }

    // ----- Helpers -----

    private static MySQLKeyedLockProvider newLock() {
        return newLock(mock(ConnectionProvider.class));
    }

    private static MySQLKeyedLockProvider newLock(ConnectionProvider provider) {
        return mySQLKeyedLockProvider().connectionProvider(provider).build();
    }

    private static ConnectionProvider newProvider(Connection conn) {
        var p = mock(ConnectionProvider.class);
        when(p.acquire()).thenReturn(conn);
        return p;
    }

    private static final class JdbcMocks {
        final Connection connection = mock(Connection.class);
        final PreparedStatement getLockStmt = mock(PreparedStatement.class);
        final PreparedStatement releaseLockStmt = mock(PreparedStatement.class);
        final ResultSet getLockResult = mock(ResultSet.class);
        final ResultSet releaseLockResult = mock(ResultSet.class);

        JdbcMocks() throws SQLException {
            when(connection.prepareStatement("SELECT GET_LOCK(?, ?)")).thenReturn(getLockStmt);
            when(connection.prepareStatement("SELECT RELEASE_LOCK(?)")).thenReturn(releaseLockStmt);
            when(getLockStmt.executeQuery()).thenReturn(getLockResult);
            when(releaseLockStmt.executeQuery()).thenReturn(releaseLockResult);
        }

        JdbcMocks getLockReturns(int value) throws SQLException {
            when(getLockResult.next()).thenReturn(true);
            when(getLockResult.getObject(1)).thenReturn(value);
            return this;
        }

        JdbcMocks getLockReturnsNull() throws SQLException {
            when(getLockResult.next()).thenReturn(true);
            when(getLockResult.getObject(1)).thenReturn(null);
            return this;
        }

        JdbcMocks releaseLockReturns(int value) throws SQLException {
            when(releaseLockResult.next()).thenReturn(true);
            when(releaseLockResult.getObject(1)).thenReturn(value);
            return this;
        }

        JdbcMocks releaseLockReturnsNull() throws SQLException {
            when(releaseLockResult.next()).thenReturn(true);
            when(releaseLockResult.getObject(1)).thenReturn(null);
            return this;
        }
    }
}
