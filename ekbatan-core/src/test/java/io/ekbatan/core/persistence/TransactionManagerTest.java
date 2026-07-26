package io.ekbatan.core.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ekbatan.core.persistence.TransactionManager.CheckedFunction;
import io.ekbatan.core.shard.ShardIdentifier;
import java.sql.Connection;
import java.sql.SQLException;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;

// Mockito + GraalVM native is blocked upstream (oracle/graal#12723). The real transaction
// behavior is exercised against a live database in :ekbatan-integration-tests-core-repo-*
// modules via Testcontainers, so native validation of transaction semantics is preserved
// there. The cases under test here are the pool-hygiene branches (evict vs release on
// rollback failure) which don't depend on a real database to verify.
@DisabledInNativeImage
class TransactionManagerTest {

    private ConnectionProvider provider;
    private Connection connection;
    private TransactionManager tm;

    @BeforeEach
    void setUp() throws SQLException {
        provider = mock(ConnectionProvider.class);
        connection = mock(Connection.class);
        when(provider.acquire()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.isClosed()).thenReturn(false);
        tm = new TransactionManager(provider, provider, SQLDialect.POSTGRES, ShardIdentifier.of(0, 0));
    }

    @Test
    void releases_connection_when_block_succeeds_and_commit_succeeds() throws Exception {
        tm.inTransactionChecked(_ -> "ok");

        verify(provider).release(connection);
        verify(provider, never()).evict(any());
    }

    @Test
    void releases_connection_when_block_throws_but_rollback_succeeds() {
        CheckedFunction<DSLContext, Object> failing = _ -> {
            throw new RuntimeException("boom");
        };

        assertThatThrownBy(() -> tm.inTransactionChecked(failing))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        verify(provider).release(connection);
        verify(provider, never()).evict(any());
    }

    @Test
    void evicts_connection_when_rollback_itself_throws_sqlexception() throws SQLException {
        // Block throws -> rollback() is called -> connection.rollback() raises SQLException ->
        // Transaction.rollback() catches it, marks dirty -> TransactionManager evicts instead of
        // returning the connection. (Note: Hikari's own return path would NOT have committed it -
        // ProxyConnection.close() rolls back before resetting autoCommit. The hazard was always
        // this class's own restore, which is why it no longer runs after a failed rollback.)
        doThrow(new SQLException("rollback failed", "55006")).when(connection).rollback();
        CheckedFunction<DSLContext, Object> failing = _ -> {
            throw new RuntimeException("boom");
        };

        assertThatThrownBy(() -> tm.inTransactionChecked(failing))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        verify(provider).evict(connection);
        verify(provider, never()).release(connection);
    }

    @Test
    void evicts_connection_when_autocommit_reset_throws_sqlexception() throws SQLException {
        // Block throws -> rollback() succeeds -> setAutoCommit(initialAutoCommit) raises ->
        // dirty=true -> evict. Same hygiene as above: a connection with the wrong autoCommit would
        // mis-behave for the next caller.
        doThrow(new SQLException("setAutoCommit failed", "08006"))
                .when(connection)
                .setAutoCommit(true);
        CheckedFunction<DSLContext, Object> failing = _ -> {
            throw new RuntimeException("boom");
        };

        assertThatThrownBy(() -> tm.inTransactionChecked(failing))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        verify(provider).evict(connection);
        verify(provider, never()).release(connection);
    }

    @Test
    void evicts_connection_when_commit_fails_and_rollback_also_fails() throws SQLException {
        // Real-world scenario: commit() throws (e.g. network glitch during 2PC-style commit),
        // catch block calls rollback() which also throws (connection truly dead). Evict.
        doThrow(new SQLException("commit failed", "08006")).when(connection).commit();
        doThrow(new SQLException("rollback failed", "08003")).when(connection).rollback();

        assertThatThrownBy(() -> tm.inTransactionChecked(_ -> "ok")).isInstanceOf(RuntimeException.class);

        verify(provider).evict(connection);
        verify(provider, never()).release(connection);
    }

    @Test
    void releases_connection_when_commit_fails_but_rollback_succeeds() throws SQLException {
        // Commit fails -> rollback succeeds -> connection is in a clean state (autocommit reset,
        // no pending tx). Safe to return to pool.
        doThrow(new SQLException("commit failed")).when(connection).commit();

        assertThatThrownBy(() -> tm.inTransactionChecked(_ -> "ok")).isInstanceOf(RuntimeException.class);

        verify(provider).release(connection);
        verify(provider, never()).evict(any());
    }

    // Restoring auto-commit is itself a commit: pgjdbc issues an explicit COMMIT, MySQL and
    // MariaDB send `SET autocommit=1`. All three were verified against live databases - an INSERT
    // followed only by setAutoCommit(true), with no commit() call anywhere, is durably committed.
    // So the reset must never run while a transaction may still be open, which is exactly the
    // state a failed rollback leaves behind.

    @Test
    void does_not_restore_autocommit_when_rollback_fails() throws SQLException {
        // A rollback that fails on a live session. Measured: on MariaDB 11.8 a `KILL QUERY` flood
        // interrupts ROLLBACK in early dispatch (error 1317, before any undo work), leaving the
        // transaction open and fully committable - 64 of 3000 rollbacks threw and 60 of those were
        // durably committed by the pre-fix code. The same flood against MySQL 8.4 produced zero
        // interrupted rollbacks, so this is a MariaDB reachability story, not a MySQL one.
        doThrow(new SQLException("rollback failed", "55006")).when(connection).rollback();
        CheckedFunction<DSLContext, Object> failing = _ -> {
            throw new RuntimeException("boom");
        };

        assertThatThrownBy(() -> tm.inTransactionChecked(failing)).isInstanceOf(RuntimeException.class);

        // The transaction is left open on purpose; evicting closes the physical connection, and
        // the server discards it. Restoring auto-commit here would commit it instead.
        verify(connection, never()).setAutoCommit(true);
        verify(provider).evict(connection);
    }

    @Test
    void restores_autocommit_after_a_successful_rollback() throws SQLException {
        CheckedFunction<DSLContext, Object> failing = _ -> {
            throw new RuntimeException("boom");
        };

        assertThatThrownBy(() -> tm.inTransactionChecked(failing)).isInstanceOf(RuntimeException.class);

        final var inOrder = inOrder(connection);
        inOrder.verify(connection).setAutoCommit(false);
        inOrder.verify(connection).rollback();
        inOrder.verify(connection).setAutoCommit(true);
        verify(provider).release(connection);
    }

    @Test
    void does_not_restore_autocommit_between_a_failed_commit_and_its_rollback() throws SQLException {
        // commit() throws with the transaction potentially still open. The reset must wait for the
        // rollback that TransactionManager is about to perform - otherwise the framework commits
        // the data it just told the caller had failed to commit, and then returns the connection
        // to the pool as if nothing happened.
        // 55006, not an 08* state: HikariCP's ProxyConnection.checkException evicts the connection
        // outright on 08* (connection-level) failures, so the ordering asserted below could never
        // arise in production with one of those. The states that actually reach this path are the
        // transaction-level ones - 1317/70100 from a killed query, 40001 from a deadlock.
        doThrow(new SQLException("commit failed", "55006")).when(connection).commit();

        assertThatThrownBy(() -> tm.inTransactionChecked(_ -> "ok")).isInstanceOf(RuntimeException.class);

        // Exactly one restore, and it belongs to the rollback - not to the failed commit.
        verify(connection, times(1)).setAutoCommit(true);
        final var inOrder = inOrder(connection);
        inOrder.verify(connection).commit();
        inOrder.verify(connection).rollback();
        inOrder.verify(connection).setAutoCommit(true);
    }

    @Test
    void no_release_or_evict_when_acquire_itself_throws() {
        when(provider.acquire()).thenThrow(new RuntimeException("pool exhausted"));

        assertThatThrownBy(() -> tm.inTransactionChecked(_ -> "ok"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("pool exhausted");

        verify(provider, never()).release(any());
        verify(provider, never()).evict(any());
    }

    @Test
    void exposes_dsl_context_to_current_transaction_when_block_running() throws Exception {
        var result = tm.inTransactionChecked(dsl -> {
            assertThat(dsl).isNotNull();
            return tm.currentTransactionDbContext();
        });
        assertThat(result).isPresent();
    }

    @Test
    void current_transaction_db_context_is_empty_outside_block() {
        assertThat(tm.currentTransactionDbContext()).isEmpty();
    }
}
