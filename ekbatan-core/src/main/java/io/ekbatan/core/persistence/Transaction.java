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
            // Nothing is sent to the server here, despite the name. Clearing auto-commit only
            // records the intent in the driver; pgjdbc defers the BEGIN and sends it attached to
            // the first statement the transaction actually executes. Same for the MySQL and
            // MariaDB drivers.
            //
            // Harmless as things stand: nothing runs between this call and the caller's first
            // query, so the BEGIN still precedes every statement that belongs to the transaction.
            // It stops being harmless the day an isolation level above READ COMMITTED is offered,
            // because REPEATABLE READ and SERIALIZABLE take their snapshot when the transaction
            // really starts - the first statement, not this line - and a caller reading rows
            // "before" opening the transaction would silently see them inside its snapshot.
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void commit() {
        try {
            connection.commit();
            // Only after the commit returned normally - see rollback() for why this must not sit
            // in a finally.
            connection.setAutoCommit(initialAutoCommit);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void rollback() {
        try {
            if (!connection.isClosed()) {
                connection.rollback();
                // Deliberately NOT in a finally. Restoring auto-commit re-enables it on a
                // connection whose transaction may still be open, and that flip is itself a
                // commit: pgjdbc issues an explicit COMMIT, MySQL and MariaDB send
                // `SET autocommit=1`, which both document as an implicit commit. Running it after
                // a failed rollback would commit the very transaction we failed to roll back.
                // Leaving auto-commit off instead keeps the transaction open until the connection
                // is evicted below, and the physical close makes the server discard it.
                connection.setAutoCommit(initialAutoCommit);
            }
        } catch (SQLException e) {
            // The connection is in unknown state: either rollback itself failed (transaction may
            // still be pending) or the autocommit reset failed (subsequent users would inherit the
            // wrong setting). Mark dirty so the caller evicts instead of returning to the pool -
            // eviction closes the physical connection, which aborts anything still pending.
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
