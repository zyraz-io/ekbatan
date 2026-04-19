package io.ekbatan.core.persistence;

import com.zaxxer.hikari.HikariDataSource;
import io.ekbatan.core.config.DataSourceConfig;
import java.sql.Connection;
import java.sql.SQLException;

public class ConnectionProvider {

    private final HikariDataSource pool;

    private ConnectionProvider(HikariDataSource pool) {
        this.pool = pool;
    }

    public Connection acquire() {
        try {
            return pool.getConnection();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire connection", e);
        }
    }

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
     */
    public void evict(Connection connection) {
        pool.evictConnection(connection);
    }

    public HikariDataSource getDataSource() {
        return pool;
    }

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
