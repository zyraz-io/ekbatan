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

    public final int member;
    public final Optional<String> name;
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
     */
    public DataSourceConfig primaryConfig() {
        return configs.get(PRIMARY_CONFIG);
    }

    /**
     * Returns the optional secondary (read-replica) datasource configuration. Empty if the user
     * did not configure one; consumers that need a read endpoint should typically fall back to
     * {@link #primaryConfig()} in that case.
     */
    public Optional<DataSourceConfig> secondaryConfig() {
        return Optional.ofNullable(configs.get(SECONDARY_CONFIG));
    }

    /**
     * Returns the datasource configuration registered under {@code name}, if any. Reserved names
     * ({@code "primaryConfig"}, {@code "secondaryConfig"}) are returned just like any other entry,
     * so this method offers a uniform lookup over every config on the member.
     */
    public Optional<DataSourceConfig> configFor(String name) {
        Validate.notBlank(name, "name cannot be blank");
        return Optional.ofNullable(configs.get(name));
    }

    public static final class Builder {

        private int member;
        private Optional<String> name = Optional.empty();
        private final Map<String, DataSourceConfig> configs = new LinkedHashMap<>();

        private Builder() {}

        public static Builder shardMemberConfig() {
            return new Builder();
        }

        public Builder member(int member) {
            this.member = member;
            return this;
        }

        public Builder name(String name) {
            this.name = Optional.of(name);
            return this;
        }

        /** Typed shortcut for the reserved {@code "primaryConfig"} entry. */
        public Builder primaryConfig(DataSourceConfig primaryConfig) {
            this.configs.put(PRIMARY_CONFIG, primaryConfig);
            return this;
        }

        /** Typed shortcut for the reserved {@code "secondaryConfig"} entry. */
        public Builder secondaryConfig(DataSourceConfig secondaryConfig) {
            this.configs.put(SECONDARY_CONFIG, secondaryConfig);
            return this;
        }

        /** Adds an arbitrary named datasource configuration alongside primary/secondary. */
        public Builder withConfig(String name, DataSourceConfig config) {
            Validate.notBlank(name, "name cannot be blank");
            this.configs.put(name, config);
            return this;
        }

        /** Bulk-replaces every entry in the configs map. */
        public Builder configs(Map<String, DataSourceConfig> configs) {
            this.configs.clear();
            this.configs.putAll(configs);
            return this;
        }

        public ShardMemberConfig build() {
            return new ShardMemberConfig(this);
        }
    }
}
