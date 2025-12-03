package io.ekbatan.core.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class Transaction {
    private final Connection connection;
    private final boolean initialAutoCommit;
    private final DSLContext dslContext;

    private static final Logger log = LoggerFactory.getLogger(Transaction.class);

    public Transaction(Connection connection) {
        this.connection = connection;
        try {
            this.initialAutoCommit = connection.getAutoCommit();
            this.dslContext = DSL.using(connection, SQLDialect.POSTGRES);
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
        } catch (SQLException e) {
            log.warn("Failed to rollback and reset auto-commit", e);
        }
    }

    public Connection connection() {
        return connection;
    }

    public DSLContext dslContext() {
        return dslContext;
    }
}
