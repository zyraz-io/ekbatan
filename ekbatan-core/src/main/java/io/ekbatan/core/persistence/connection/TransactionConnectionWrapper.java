package io.ekbatan.core.persistence.connection;

import java.sql.Connection;
import java.sql.SQLException;

public class TransactionConnectionWrapper {
    private final Connection connection;
    private final boolean initialAutoCommit;

    public TransactionConnectionWrapper(Connection connection) {
        this.connection = connection;
        try {
            this.initialAutoCommit = connection.getAutoCommit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void begin() {
        try {
            // when we set autoCommit = false, we are implicitly signaling the db that we want a transaction
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void commit() {
        try {
            try {
                connection.commit();
            } finally {
                connection.setAutoCommit(initialAutoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void rollback() {
        try {
            if (!connection.isClosed()) {
                try {
                    connection.rollback();
                } finally {
                    connection.setAutoCommit(initialAutoCommit);
                }
            }
        } catch (SQLException ignored) {
        }
    }

    public Connection connection() {
        return connection;
    }
}
