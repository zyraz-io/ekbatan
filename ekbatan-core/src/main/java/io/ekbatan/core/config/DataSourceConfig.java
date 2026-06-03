package io.ekbatan.core.config;

import io.ekbatan.core.internal.Validate;
import java.util.Optional;
import org.jooq.SQLDialect;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

/**
 * HikariCP-compatible configuration with auto-resolved dialect from JDBC URL.
 */
@JsonDeserialize(builder = DataSourceConfig.Builder.class)
public final class DataSourceConfig {

    /** The JDBC connection URL (e.g. {@code jdbc:postgresql://...}). */
    public final String jdbcUrl;

    /** Database username. */
    public final String username;

    /** Database password. */
    public final String password;

    /** jOOQ {@link SQLDialect} resolved from {@link #jdbcUrl}; consulted by the framework for dialect-specific SQL. */
    public final SQLDialect dialect;

    /** Optional explicit driver class name; HikariCP resolves automatically when absent. */
    public final Optional<String> driverClassName;

    /** Maximum size of the Hikari pool (default 10). */
    public final int maximumPoolSize;

    /** Optional minimum number of idle connections kept warm. */
    public final Optional<Integer> minimumIdle;

    /** Optional idle timeout in milliseconds. */
    public final Optional<Long> idleTimeout;

    /** Optional leak-detection threshold in milliseconds; logs a warning when a held connection exceeds it. */
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

    /** Fluent builder for {@link DataSourceConfig}. Obtain via {@link #dataSourceConfig()}. */
    @JsonPOJOBuilder(withPrefix = "")
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

        /** {@return a fresh builder for {@link DataSourceConfig}} */
        public static Builder dataSourceConfig() {
            return new Builder();
        }

        /**
         * Sets the JDBC URL. Required.
         *
         * @param jdbcUrl the JDBC URL.
         * @return this builder, for chaining.
         */
        public Builder jdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            return this;
        }

        /**
         * Sets the database username. Required.
         *
         * @param username the database username.
         * @return this builder, for chaining.
         */
        public Builder username(String username) {
            this.username = username;
            return this;
        }

        /**
         * Sets the database password. Required (may be the empty string).
         *
         * @param password the database password.
         * @return this builder, for chaining.
         */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /**
         * Overrides Hikari's automatic driver resolution.
         *
         * @param driverClassName the JDBC driver class name.
         * @return this builder, for chaining.
         */
        public Builder driverClassName(String driverClassName) {
            this.driverClassName = Optional.of(driverClassName);
            return this;
        }

        /**
         * Sets the Hikari maximum pool size (default 10).
         *
         * @param maximumPoolSize the maximum pool size.
         * @return this builder, for chaining.
         */
        public Builder maximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
            return this;
        }

        /**
         * Sets the Hikari minimum idle connection count.
         *
         * @param minimumIdle the minimum idle count.
         * @return this builder, for chaining.
         */
        public Builder minimumIdle(int minimumIdle) {
            this.minimumIdle = Optional.of(minimumIdle);
            return this;
        }

        /**
         * Sets the Hikari idle timeout in milliseconds.
         *
         * @param idleTimeout the idle timeout in milliseconds.
         * @return this builder, for chaining.
         */
        public Builder idleTimeout(long idleTimeout) {
            this.idleTimeout = Optional.of(idleTimeout);
            return this;
        }

        /**
         * Sets the Hikari leak-detection threshold in milliseconds.
         *
         * @param leakDetectionThreshold the leak-detection threshold in milliseconds.
         * @return this builder, for chaining.
         */
        public Builder leakDetectionThreshold(long leakDetectionThreshold) {
            this.leakDetectionThreshold = Optional.of(leakDetectionThreshold);
            return this;
        }

        /** {@return a configured {@link DataSourceConfig}; throws if required fields are unset} */
        public DataSourceConfig build() {
            return new DataSourceConfig(this);
        }
    }
}
