package io.ekbatan.core.config;

import java.util.Optional;
import org.apache.commons.lang3.Validate;
import org.jooq.SQLDialect;

/**
 * HikariCP-compatible configuration with auto-resolved dialect from JDBC URL.
 */
public final class DataSourceConfig {

    public final String jdbcUrl;
    public final String username;
    public final String password;
    public final SQLDialect dialect;
    public final Optional<String> driverClassName;
    public final int maximumPoolSize;
    public final Optional<Integer> minimumIdle;
    public final Optional<Long> idleTimeout;
    public final Optional<Long> leakDetectionThreshold;

    private DataSourceConfig(Builder builder) {
        this.jdbcUrl = Validate.notBlank(builder.jdbcUrl, "jdbcUrl is required");
        this.username = Validate.notBlank(builder.username, "username is required");
        this.password = Validate.notNull(builder.password, "password is required");
        this.dialect = resolveDialect(this.jdbcUrl);
        this.driverClassName = builder.driverClassName;
        this.maximumPoolSize = builder.maximumPoolSize;
        this.minimumIdle = builder.minimumIdle;
        this.idleTimeout = builder.idleTimeout;
        this.leakDetectionThreshold = builder.leakDetectionThreshold;
    }

    private static SQLDialect resolveDialect(String jdbcUrl) {
        if (jdbcUrl.contains("postgresql")) return SQLDialect.POSTGRES;
        if (jdbcUrl.contains("mysql")) return SQLDialect.MYSQL;
        if (jdbcUrl.contains("mariadb")) return SQLDialect.MARIADB;
        throw new IllegalArgumentException("Cannot determine dialect from URL: " + jdbcUrl);
    }

    public static final class Builder {

        private String jdbcUrl;
        private String username;
        private String password;
        private Optional<String> driverClassName = Optional.empty();
        private int maximumPoolSize = 10;
        private Optional<Integer> minimumIdle = Optional.empty();
        private Optional<Long> idleTimeout = Optional.empty();
        private Optional<Long> leakDetectionThreshold = Optional.empty();

        private Builder() {}

        public static Builder dataSourceConfig() {
            return new Builder();
        }

        public Builder jdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder driverClassName(String driverClassName) {
            this.driverClassName = Optional.of(driverClassName);
            return this;
        }

        public Builder maximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
            return this;
        }

        public Builder minimumIdle(int minimumIdle) {
            this.minimumIdle = Optional.of(minimumIdle);
            return this;
        }

        public Builder idleTimeout(long idleTimeout) {
            this.idleTimeout = Optional.of(idleTimeout);
            return this;
        }

        public Builder leakDetectionThreshold(long leakDetectionThreshold) {
            this.leakDetectionThreshold = Optional.of(leakDetectionThreshold);
            return this;
        }

        public DataSourceConfig build() {
            return new DataSourceConfig(this);
        }
    }
}
