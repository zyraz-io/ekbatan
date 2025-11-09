package io.ekbatan.core.persistence.jdbc;

import io.ekbatan.core.persistence.ConnectionHolder;
import io.ekbatan.core.persistence.ConnectionMode;
import io.ekbatan.core.persistence.TransactionManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;

/**
 * JDBC implementation of {@link TransactionManager} that manages database transactions.
 */
public class JdbcTransactionManager implements TransactionManager<Connection> {
    private static final String READ_WRITE_KEY = JdbcTransactionManager.class.getName() + "_READ_WRITE";
    private static final String READ_ONLY_KEY = JdbcTransactionManager.class.getName() + "_READ_ONLY";

    private final JdbcConnectionProvider readWriteConnectionProvider;
    private final JdbcConnectionProvider readOnlyConnectionProvider;

    /**
     * Creates a new JdbcTransactionManager with the specified connection providers.
     *
     * @param readWriteConnectionProvider the provider for read-write connections
     * @param readOnlyConnectionProvider the provider for read-only connections
     */
    public JdbcTransactionManager(
            JdbcConnectionProvider readWriteConnectionProvider, JdbcConnectionProvider readOnlyConnectionProvider) {
        this.readWriteConnectionProvider = readWriteConnectionProvider;
        this.readOnlyConnectionProvider = readOnlyConnectionProvider;
    }

    @Override
    public <T> T withTransaction(Function<Connection, T> action) {
        return withTransaction(ConnectionMode.REQUIRE_NEW, action);
    }

    @Override
    public <T> T withTransaction(ConnectionMode mode, Function<Connection, T> action) {
        if (ConnectionMode.REQUIRE_EXISTING.equals(mode) && !isTransactionActive()) {
            throw new IllegalStateException("No existing transaction found for mode: " + mode);
        }

        if (isTransactionActive()) {
            return action.apply(getCurrentConnection());
        }

        Connection conn = null;
        try {
            conn = beginTransaction();
            T result = action.apply(conn);
            commitTransaction(conn);
            return result;
        } catch (Exception e) {
            if (conn != null) {
                rollbackTransaction(conn);
            }
            throw new TransactionException("Transaction failed", e);
        } finally {
            if (conn != null && !isTransactionActive()) {
                cleanupTransaction(conn);
            }
        }
    }

    /**
     * Begins a new transaction.
     *
     * @return the connection associated with the new transaction
     * @throws TransactionException if the transaction cannot be started
     */
    private Connection beginTransaction() {
        try {
            Connection connection = readWriteConnectionProvider.acquire();
            connection.setAutoCommit(false);
            ConnectionHolder.bindResource(READ_WRITE_KEY, connection);
            return connection;
        } catch (SQLException e) {
            throw new TransactionException("Failed to begin transaction", e);
        } catch (Exception e) {
            throw new TransactionException("Failed to acquire connection", e);
        }
    }

    /**
     * Commits the current transaction.
     *
     * @param connection the connection to commit
     * @throws TransactionException if the commit fails
     */
    private void commitTransaction(Connection connection) {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.commit();
            }
        } catch (SQLException e) {
            throw new TransactionException("Failed to commit transaction", e);
        } finally {
            cleanupTransaction(connection);
        }
    }

    /**
     * Rolls back the current transaction.
     *
     * @param connection the connection to roll back
     * @throws TransactionException if the rollback fails
     */
    private void rollbackTransaction(Connection connection) {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.rollback();
            }
        } catch (SQLException e) {
            throw new TransactionException("Failed to rollback transaction", e);
        } finally {
            cleanupTransaction(connection);
        }
    }

    /**
     * Cleans up transaction resources.
     *
     * @param connection the connection to clean up
     */
    private void cleanupTransaction(Connection connection) {
        try {
            ConnectionHolder.unbindResource(READ_WRITE_KEY);
            if (connection != null && !connection.isClosed()) {
                connection.setAutoCommit(true);
                readWriteConnectionProvider.release(connection);
            }
        } catch (SQLException e) {
            throw new TransactionException("Failed to cleanup transaction", e);
        }
    }

    /**
     * Gets a read-only connection.
     *
     * @return a read-only connection
     * @throws TransactionException if the connection cannot be obtained
     */
    public Connection getReadOnlyConnection() {
        if (ConnectionHolder.hasResource(READ_ONLY_KEY)) {
            return getCurrentReadOnlyConnection();
        }

        try {
            Connection connection = readOnlyConnectionProvider.acquire();
            connection.setReadOnly(true);
            ConnectionHolder.bindResource(READ_ONLY_KEY, connection);
            return connection;
        } catch (SQLException e) {
            throw new TransactionException("Failed to get read-only connection", e);
        }
    }

    /**
     * Releases a read-only connection.
     */
    public void releaseReadOnlyConnection() {
        if (!ConnectionHolder.hasResource(READ_ONLY_KEY)) {
            return;
        }
        Connection connection = getCurrentReadOnlyConnection();
        ConnectionHolder.unbindResource(READ_ONLY_KEY);
        readOnlyConnectionProvider.release(connection);
    }

    /**
     * Checks if there is an active transaction.
     *
     * @return true if there is an active transaction, false otherwise
     */
    public boolean isTransactionActive() {
        return ConnectionHolder.hasResource(READ_WRITE_KEY);
    }

    /**
     * Gets the current connection associated with the transaction.
     *
     * @return the current connection
     * @throws IllegalStateException if there is no active transaction
     */
    public Connection getCurrentConnection() {
        Connection connection = ConnectionHolder.getResource(READ_WRITE_KEY);
        if (connection == null) {
            throw new IllegalStateException("No active transaction");
        }
        return connection;
    }

    /**
     * Gets the current read-only connection.
     *
     * @return the current read-only connection
     * @throws IllegalStateException if there is no active read-only connection
     */
    public Connection getCurrentReadOnlyConnection() {
        Connection connection = ConnectionHolder.getResource(READ_ONLY_KEY);
        if (connection == null) {
            throw new IllegalStateException("No active read-only connection");
        }
        return connection;
    }
}
