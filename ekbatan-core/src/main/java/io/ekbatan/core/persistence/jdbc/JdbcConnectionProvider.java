package io.ekbatan.core.persistence.jdbc;

import io.ekbatan.core.persistence.ConnectionProvider;
import java.sql.Connection;

/**
 * JDBC-specific connection provider interface.
 */
public interface JdbcConnectionProvider extends ConnectionProvider<Connection> {
    /**
     * Acquires a JDBC connection from the pool.
     *
     * @return a JDBC connection
     * @throws ConnectionProvider.ConnectionException if a connection could not be obtained
     */
    @Override
    Connection acquire();

    /**
     * Releases a JDBC connection back to the pool.
     *
     * @param connection the connection to release
     */
    @Override
    void release(Connection connection);
}
