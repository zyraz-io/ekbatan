package io.ekbatan.core.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        // Block throws → rollback() is called → connection.rollback() raises SQLException →
        // Transaction.rollback() catches it, marks dirty → TransactionManager evicts instead of
        // returning the connection (where Hikari's setAutoCommit(true) reset could implicitly
        // commit the failed-rollback transaction).
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
        // Block throws → rollback() succeeds → setAutoCommit(initialAutoCommit) raises →
        // dirty=true → evict. Same hygiene as above: a connection with the wrong autoCommit would
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
        // Commit fails → rollback succeeds → connection is in a clean state (autocommit reset,
        // no pending tx). Safe to return to pool.
        doThrow(new SQLException("commit failed")).when(connection).commit();

        assertThatThrownBy(() -> tm.inTransactionChecked(_ -> "ok")).isInstanceOf(RuntimeException.class);

        verify(provider).release(connection);
        verify(provider, never()).evict(any());
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
