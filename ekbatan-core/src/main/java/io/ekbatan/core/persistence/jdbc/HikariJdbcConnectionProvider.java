package io.ekbatan.core.persistence.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.ekbatan.core.persistence.ConnectionProvider;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * HikariCP-based implementation of {@link JdbcConnectionProvider}.
 */
public class HikariJdbcConnectionProvider implements JdbcConnectionProvider {
    private final HikariDataSource dataSource;

    /**
     * Creates a new HikariJdbcConnectionProvider with the given configuration.
     *
     * @param config the HikariCP configuration
     */
    public HikariJdbcConnectionProvider(HikariConfig config) {
        this.dataSource = new HikariDataSource(config);
    }

    @Override
    public Connection acquire() {
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw new ConnectionException("Failed to acquire database connection", e);
        }
    }

    @Override
    public void release(Connection connection) {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            throw new ConnectionException("Failed to release database connection", e);
        }
    }

    /**
     * Shuts down the connection pool and releases all resources.
     */
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    /**
     * Exception thrown when a connection cannot be acquired.
     */
    public static class ConnectionException extends ConnectionProvider.ConnectionException {
        public ConnectionException(String message) {
            super(message);
        }

        public ConnectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class ConnectionReleaseException extends RuntimeException {
        public ConnectionReleaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
