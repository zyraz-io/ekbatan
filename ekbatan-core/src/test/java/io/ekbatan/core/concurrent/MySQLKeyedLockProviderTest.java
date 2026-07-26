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
import org.junit.jupiter.api.condition.DisabledInNativeImage;

// See ActionExecutorTest for the full rationale: Mockito + GraalVM native is blocked
// upstream on JDK 25 (oracle/graal#12723) and on JDK 26 EA. The real provider contract
// is exercised against a live MySQL in
// ekbatan-integration-tests:keyed-lock-provider:mysql (Testcontainers), so native
// validation of the locking semantics is preserved.
@DisabledInNativeImage
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
                .isInstanceOf(LockAcquisitionException.class)
                .hasMessageContaining("Failed to acquire lock for key k")
                .hasCauseInstanceOf(SQLException.class);

        verify(provider).release(jdbc.connection);
        verify(provider, never()).evict(any());
    }

    @Test
    void acquire_zero_response_should_retry_until_acquired() throws Exception {
        // 0 means "the segment timed out", which is the normal path for a lock held by
        // somebody else - acquire() keeps waiting rather than failing. The second segment
        // gets it, proving the loop retries instead of surfacing a timeout as an error.
        var jdbc = new JdbcMocks().getLockReturnsInOrder(0, 1);
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var lease = lock.acquire("k", ONE_HOUR);

        assertThat(lease.isHeld()).isTrue();
        verify(jdbc.getLockStmt, times(2)).executeQuery();

        lease.close();
    }

    @Test
    void acquire_null_response_should_release_and_throw() throws Exception {
        // NULL is a server-side error, not a timeout. It must not be retried, or acquire()
        // would spin forever against a broken server.
        var jdbc = new JdbcMocks().getLockReturnsNull();
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        assertThatThrownBy(() -> lock.acquire("k", ONE_HOUR))
                .isInstanceOf(LockAcquisitionException.class)
                .hasMessageContaining("server-side error");

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
    void try_acquire_null_response_should_throw() throws Exception {
        // NULL indicates a server-side error, not contention. Reporting it as "didn't get the
        // lock" would let a broken server masquerade as a busy one - the caller's fallback
        // path would run as if another holder were simply ahead of it.
        var jdbc = new JdbcMocks().getLockReturnsNull();
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        assertThatThrownBy(() -> lock.tryAcquire("k", ONE_SECOND, ONE_HOUR))
                .isInstanceOf(LockAcquisitionException.class)
                .hasMessageContaining("server-side error");

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

    // ----- acquire() waits in bounded segments -----

    @Test
    void acquire_should_wait_in_bounded_segments_not_forever() throws Exception {
        // The indefinite-block semantic is preserved by looping, not by passing a "wait
        // forever" sentinel. Each segment is finite so the loop can observe interruption
        // between them.
        var jdbc = new JdbcMocks().getLockReturns(1);
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        var lease = lock.acquire("k", ONE_HOUR);

        verify(jdbc.getLockStmt).setDouble(2, 5.0);

        lease.close();
    }

    @Test
    void acquire_should_throw_when_already_interrupted() throws Exception {
        var jdbc = new JdbcMocks().getLockReturns(1);
        var provider = newProvider(jdbc.connection);
        var lock = newLock(provider);

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> lock.acquire("k", ONE_HOUR)).isInstanceOf(InterruptedException.class);

            // AND - no backend call was made, and no holder state was left behind
            verify(jdbc.getLockStmt, never()).executeQuery();
        } finally {
            Thread.interrupted(); // clear for subsequent tests
        }
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

    @Test
    void acquire_failure_should_not_be_masked_by_connection_release_failure() throws Exception {
        // Returning a broken connection can itself throw - and that is exactly this path.
        // The SQL error that explains the failed acquisition must survive; the cleanup
        // failure rides along as a suppressed exception.
        var jdbc = new JdbcMocks();
        doThrow(new SQLException("boom")).when(jdbc.getLockStmt).executeQuery();
        var provider = newProvider(jdbc.connection);
        doThrow(new RuntimeException("pool exploded")).when(provider).release(any());
        var lock = newLock(provider);

        assertThatThrownBy(() -> lock.acquire("k", ONE_HOUR))
                .isInstanceOf(LockAcquisitionException.class)
                .hasCauseInstanceOf(SQLException.class)
                .satisfies(thrown -> {
                    assertThat(thrown.getSuppressed()).hasSize(1);
                    assertThat(thrown.getSuppressed()[0]).hasMessageContaining("pool exploded");
                });
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

        /** Answers successive {@code GET_LOCK} calls with the given values, for testing the acquire retry loop. */
        JdbcMocks getLockReturnsInOrder(int first, int... rest) throws SQLException {
            when(getLockResult.next()).thenReturn(true);
            final var remaining = new Object[rest.length];
            for (var i = 0; i < rest.length; i++) {
                remaining[i] = rest[i];
            }
            when(getLockResult.getObject(1)).thenReturn(first, remaining);
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
