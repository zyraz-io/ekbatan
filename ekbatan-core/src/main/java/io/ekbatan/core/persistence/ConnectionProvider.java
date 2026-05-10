package io.ekbatan.core.persistence;

import com.zaxxer.hikari.HikariDataSource;
import io.ekbatan.core.config.DataSourceConfig;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Owns a HikariCP connection pool and exposes acquire / release / evict / close on top of it.
 * Each {@link TransactionManager} holds a pair (primary + optional read-replica secondary);
 * the framework wraps individual {@code Connection}s through {@code acquire()} / {@code
 * release()} rather than handing the {@link HikariDataSource} out directly so connection
 * lifecycle is centralised here.
 *
 * <p>Construct via {@link #hikariConnectionProvider(DataSourceConfig)} — the canonical
 * factory wires {@code jdbcUrl}, {@code username}, {@code password}, pool sizes, and leak-
 * detection threshold straight from a {@link DataSourceConfig}. The constructor is private,
 * so the factory method is the only public construction path; the pool implementation is
 * HikariCP and not pluggable from outside this class.
 *
 * <p>{@link #close()} is idempotent; the registry closes every provider on application
 * shutdown.
 */
public class ConnectionProvider implements AutoCloseable {

    private final HikariDataSource pool;

    private ConnectionProvider(HikariDataSource pool) {
        this.pool = pool;
    }

    /** {@return a fresh JDBC {@link Connection} drawn from the underlying pool} */
    public Connection acquire() {
        try {
            return pool.getConnection();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire connection", e);
        }
    }

    /**
     * Returns a connection to the pool. The underlying Hikari {@code ProxyConnection.close()}
     * recycles the physical connection rather than actually closing it.
     *
     * @param connection a connection previously obtained via {@link #acquire()}.
     */
    public void release(Connection connection) {
        try {
            // Hikari have ProxyConnection which implements Connection interface,
            // close function in that class would return connection to the pool
            connection.close();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to release connection", e);
        }
    }

    /**
     * Forcibly removes the connection from the pool instead of returning it. The underlying
     * physical connection is closed, and the pool will create a replacement to maintain its
     * configured size. Use this when a connection is suspected to be in a corrupted or unknown
     * state (e.g., a SQL error left it with leftover session settings, or a release operation
     * itself failed).
     *
     * @param connection a connection previously obtained via {@link #acquire()}.
     */
    public void evict(Connection connection) {
        pool.evictConnection(connection);
    }

    /** {@return the underlying Hikari {@link HikariDataSource}; rarely needed — prefer acquire/release} */
    public HikariDataSource getDataSource() {
        return pool;
    }

    /**
     * Closes the underlying Hikari pool. Idempotent — safe to call multiple times.
     * After {@code close()}, no further {@link #acquire()} calls are valid.
     */
    @Override
    public void close() {
        pool.close();
    }

    /**
     * Canonical factory: builds a Hikari-backed provider from a {@link DataSourceConfig}.
     *
     * @param cfg the data-source configuration.
     * @return a fresh provider with its own pool; the caller owns lifecycle and must {@link #close()} it.
     */
    public static ConnectionProvider hikariConnectionProvider(DataSourceConfig cfg) {
        var hikari = new com.zaxxer.hikari.HikariConfig();
        hikari.setJdbcUrl(cfg.jdbcUrl);
        hikari.setUsername(cfg.username);
        hikari.setPassword(cfg.password);
        cfg.driverClassName.ifPresent(hikari::setDriverClassName);
        hikari.setMaximumPoolSize(cfg.maximumPoolSize);
        hikari.setInitializationFailTimeout(-1);
        cfg.minimumIdle.ifPresent(hikari::setMinimumIdle);
        cfg.idleTimeout.ifPresent(hikari::setIdleTimeout);
        cfg.leakDetectionThreshold.ifPresent(hikari::setLeakDetectionThreshold);
        return new ConnectionProvider(new HikariDataSource(hikari));
    }
}
