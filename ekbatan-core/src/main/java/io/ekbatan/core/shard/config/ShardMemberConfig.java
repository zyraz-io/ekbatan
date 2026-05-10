package io.ekbatan.core.shard.config;

import io.ekbatan.core.config.DataSourceConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.Validate;

/**
 * Configuration for a single shard member.
 *
 * <p>All datasource configurations live in a single named {@link #configs} map. {@code
 * "primaryConfig"} is reserved and required; {@code "secondaryConfig"} is reserved and optional.
 * Any other named entry (e.g. {@code "lockConfig"}, {@code "analyticsConfig"}) is user-defined
 * and accessed via {@link #configFor(String)}.
 *
 * <p>YAML form maps directly to this Java structure:
 * <pre>
 * - member: 0
 *   name: global-eu-1
 *   configs:
 *     primaryConfig:   { jdbcUrl: ..., username: ..., password: ..., maximumPoolSize: 20 }
 *     secondaryConfig: { jdbcUrl: ..., ... }
 *     lockConfig:      { jdbcUrl: ..., maximumPoolSize: 50, leakDetectionThreshold: 0 }
 * </pre>
 */
public final class ShardMemberConfig {

    /** Reserved key for the required primary datasource configuration. */
    public static final String PRIMARY_CONFIG = "primaryConfig";

    /** Reserved key for the optional secondary (read-replica) datasource configuration. */
    public static final String SECONDARY_CONFIG = "secondaryConfig";

    /** Numeric key for this member within the group. */
    public final int member;

    /** Optional human-readable name (e.g. {@code "us-east-primary"}). */
    public final Optional<String> name;

    /** All datasource configurations on this member, keyed by name. */
    public final Map<String, DataSourceConfig> configs;

    private ShardMemberConfig(Builder builder) {
        this.member = builder.member;
        this.name = builder.name;
        Validate.isTrue(builder.configs.containsKey(PRIMARY_CONFIG), "configs.%s is required", PRIMARY_CONFIG);
        this.configs = Map.copyOf(builder.configs);
    }

    /**
     * Returns the required primary datasource configuration. Guaranteed non-null by build-time
     * validation.
     *
     * @return the primary datasource configuration.
     */
    public DataSourceConfig primaryConfig() {
        return configs.get(PRIMARY_CONFIG);
    }

    /**
     * Returns the optional secondary (read-replica) datasource configuration. Empty if the user
     * did not configure one; consumers that need a read endpoint should typically fall back to
     * {@link #primaryConfig()} in that case.
     *
     * @return the optional secondary datasource configuration.
     */
    public Optional<DataSourceConfig> secondaryConfig() {
        return Optional.ofNullable(configs.get(SECONDARY_CONFIG));
    }

    /**
     * Returns the datasource configuration registered under {@code name}, if any. Reserved names
     * ({@code "primaryConfig"}, {@code "secondaryConfig"}) are returned just like any other entry,
     * so this method offers a uniform lookup over every config on the member.
     *
     * @param name the configuration name to look up.
     * @return the matching datasource configuration, or empty.
     */
    public Optional<DataSourceConfig> configFor(String name) {
        Validate.notBlank(name, "name cannot be blank");
        return Optional.ofNullable(configs.get(name));
    }

    /** Fluent builder for {@link ShardMemberConfig}. Obtain via {@link #shardMemberConfig()}. */
    public static final class Builder {

        private int member;
        private Optional<String> name = Optional.empty();
        private final Map<String, DataSourceConfig> configs = new LinkedHashMap<>();

        private Builder() {}

        /** {@return a fresh builder for {@link ShardMemberConfig}} */
        public static Builder shardMemberConfig() {
            return new Builder();
        }

        /**
         * Sets the member's numeric key.
         *
         * @param member the member key.
         * @return this builder, for chaining.
         */
        public Builder member(int member) {
            this.member = member;
            return this;
        }

        /**
         * Sets the member's human-readable name.
         *
         * @param name the name.
         * @return this builder, for chaining.
         */
        public Builder name(String name) {
            this.name = Optional.of(name);
            return this;
        }

        /**
         * Typed shortcut for the reserved {@code "primaryConfig"} entry.
         *
         * @param primaryConfig the primary datasource configuration.
         * @return this builder, for chaining.
         */
        public Builder primaryConfig(DataSourceConfig primaryConfig) {
            this.configs.put(PRIMARY_CONFIG, primaryConfig);
            return this;
        }

        /**
         * Typed shortcut for the reserved {@code "secondaryConfig"} entry.
         *
         * @param secondaryConfig the secondary datasource configuration.
         * @return this builder, for chaining.
         */
        public Builder secondaryConfig(DataSourceConfig secondaryConfig) {
            this.configs.put(SECONDARY_CONFIG, secondaryConfig);
            return this;
        }

        /**
         * Adds an arbitrary named datasource configuration alongside primary/secondary.
         *
         * @param name the entry name.
         * @param config the datasource configuration.
         * @return this builder, for chaining.
         */
        public Builder withConfig(String name, DataSourceConfig config) {
            Validate.notBlank(name, "name cannot be blank");
            this.configs.put(name, config);
            return this;
        }

        /**
         * Bulk-replaces every entry in the configs map.
         *
         * @param configs the new configs map.
         * @return this builder, for chaining.
         */
        public Builder configs(Map<String, DataSourceConfig> configs) {
            this.configs.clear();
            this.configs.putAll(configs);
            return this;
        }

        /** {@return a configured {@link ShardMemberConfig}; throws if {@code primaryConfig} is unset} */
        public ShardMemberConfig build() {
            return new ShardMemberConfig(this);
        }
    }
}
