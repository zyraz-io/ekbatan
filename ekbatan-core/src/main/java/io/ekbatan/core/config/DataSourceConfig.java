package io.ekbatan.core.config;

import java.util.Optional;

/**
 * HikariCP-compatible configuration
 */
public record DataSourceConfig(
        String jdbcUrl,
        String username,
        String password,
        Optional<String> driverClassName, // optional
        Integer maximumPoolSize, // required
        Optional<Integer> minimumIdle, // optional
        Optional<Long> idleTimeout, // optional
        Optional<Long> leakDetectionThreshold // optional
        ) {}
