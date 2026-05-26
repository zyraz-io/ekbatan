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
    private boolean dirty = false;

    private static final Logger log = LoggerFactory.getLogger(Transaction.class);

    public Transaction(Connection connection, SQLDialect dialect) {
        this.connection = connection;
        try {
            this.initialAutoCommit = connection.getAutoCommit();
            this.dslContext = DSL.using(connection, dialect);
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
            // The connection is in unknown state: either rollback itself failed (transaction may
            // still be pending) or the autocommit reset failed (subsequent users would inherit the
            // wrong setting). Mark dirty so the caller evicts instead of returning to the pool -
            // otherwise Hikari's setAutoCommit(true) reset on return would implicitly commit a
            // partially-rolled-back transaction.
            log.warn("Failed to rollback and reset auto-commit; connection will be evicted", e);
            this.dirty = true;
        }
    }

    /**
     * Returns {@code true} when an internal SQL operation (rollback or autocommit reset) raised an
     * exception, leaving the underlying connection in unknown state. The caller should evict the
     * connection from the pool rather than returning it.
     */
    public boolean isDirty() {
        return dirty;
    }

    public Connection connection() {
        return connection;
    }

    public DSLContext dslContext() {
        return dslContext;
    }
}
