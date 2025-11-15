package io.ekbatan.core.config;

/**
 * HikariCP-compatible configuration
 */
public record DataSourceConfig(
        String jdbcUrl,
        String username,
        String password,
        String driverClassName, // optional
        Integer maximumPoolSize, // required
        Integer minimumIdle, // optional
        Long idleTimeout, // optional
        Long leakDetectionThreshold // optional
        ) {}
