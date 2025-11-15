package io.ekbatan.core.persistence.connection;

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
            connection.close();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to release connection", e);
        }
    }

    public static ConnectionProvider hikariConnectionProvider(DataSourceConfig cfg, boolean primary) {
        var hikari = new com.zaxxer.hikari.HikariConfig();
        hikari.setJdbcUrl(cfg.jdbcUrl()
                + (primary ? "?targetServerType=master" : "?targetServerType=preferSlave&loadBalanceHosts=true"));
        hikari.setUsername(cfg.username());
        hikari.setPassword(cfg.password());
        if (cfg.driverClassName() != null) {
            hikari.setDriverClassName(cfg.driverClassName());
        }
        hikari.setMaximumPoolSize(cfg.maximumPoolSize());
        if (cfg.minimumIdle() != null) {
            hikari.setMinimumIdle(cfg.minimumIdle());
        }
        if (cfg.idleTimeout() != null) {
            hikari.setIdleTimeout(cfg.idleTimeout());
        }
        if (cfg.leakDetectionThreshold() != null) {
            hikari.setLeakDetectionThreshold(cfg.leakDetectionThreshold());
        }
        return new ConnectionProvider(new HikariDataSource(hikari));
    }
}
