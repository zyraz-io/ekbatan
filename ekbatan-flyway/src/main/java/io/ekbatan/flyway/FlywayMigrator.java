package io.ekbatan.flyway;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.config.ShardingConfig;
import io.ekbatan.core.shard.ShardIdentifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Flyway migration utility for Ekbatan applications.
 *
 * <p>The same class covers the common cases:
 * <ul>
 *   <li>one datasource, via {@link #migrate(DataSourceConfig, String...)};
 *   <li>all primary datasources in a {@link ShardingConfig}, via
 *       {@link #migrate(ShardingConfig, String...)};
 *   <li>an explicit set of shard targets, via {@link #migrate(Target...)} or the builder.
 * </ul>
 *
 * <p>On the JVM this is a thin wrapper around normal Flyway configuration. Inside a GraalVM native
 * image it installs an internal resource scanner automatically so Flyway can enumerate bundled
 * {@code classpath:} migrations.
 */
public final class FlywayMigrator {

    /** Conventional Flyway migration location used when no locations are supplied. */
    public static final String DEFAULT_LOCATION = "classpath:db/migration";

    private FlywayMigrator() {}

    /**
     * Runs Flyway against one datasource.
     *
     * @param dataSourceConfig the target datasource.
     * @param locations optional Flyway locations; defaults to {@link #DEFAULT_LOCATION}.
     * @return Flyway's migration result.
     */
    public static MigrateResult migrate(DataSourceConfig dataSourceConfig, String... locations) {
        Objects.requireNonNull(dataSourceConfig, "dataSourceConfig is required");
        return migrate(dataSourceConfig.jdbcUrl, dataSourceConfig.username, dataSourceConfig.password, locations);
    }

    /**
     * Runs Flyway against one datasource.
     *
     * @param jdbcUrl the JDBC URL of the target database.
     * @param username the database username used to apply migrations.
     * @param password the database password.
     * @param locations optional Flyway locations; defaults to {@link #DEFAULT_LOCATION}.
     * @return Flyway's migration result.
     */
    public static MigrateResult migrate(String jdbcUrl, String username, String password, String... locations) {
        return migrateOne(jdbcUrl, username, password, normalizeLocations(locations), cfg -> {});
    }

    /**
     * Runs Flyway against every primary shard in {@code shardingConfig}, sequentially and fail-fast.
     *
     * @param shardingConfig the Ekbatan sharding config.
     * @param locations optional Flyway locations; defaults to {@link #DEFAULT_LOCATION}.
     * @return one result per migrated shard.
     */
    public static List<Result> migrate(ShardingConfig shardingConfig, String... locations) {
        return builder().withShardingConfig(shardingConfig).locations(locations).migrate();
    }

    /**
     * Runs Flyway against the supplied shard targets, sequentially and fail-fast.
     *
     * @param targets explicit shard targets to migrate.
     * @return one result per migrated target.
     */
    public static List<Result> migrate(Target... targets) {
        return builder().withTargets(targets).migrate();
    }

    /**
     * Runs Flyway against the supplied shard targets, sequentially and fail-fast.
     *
     * @param targets explicit shard targets to migrate.
     * @return one result per migrated target.
     */
    public static List<Result> migrate(Collection<Target> targets) {
        return builder().withTargets(targets).migrate();
    }

    /**
     * Builds one target per primary shard in {@code shardingConfig}.
     *
     * @param shardingConfig the Ekbatan sharding config.
     * @return targets in the same group/member order as the config.
     */
    public static List<Target> targets(ShardingConfig shardingConfig) {
        Objects.requireNonNull(shardingConfig, "shardingConfig is required");
        List<Target> targets = new ArrayList<>();
        for (var group : shardingConfig.groups) {
            for (var member : group.members) {
                var shard = ShardIdentifier.of(group.group, member.member);
                var name = group.name + "/" + member.name.orElse("member-" + member.member);
                targets.add(target(shard, name, member.primaryConfig()));
            }
        }
        return List.copyOf(targets);
    }

    /**
     * Creates a target for a datasource on the default shard.
     *
     * @param dataSourceConfig target datasource.
     * @return a migration target.
     */
    public static Target target(DataSourceConfig dataSourceConfig) {
        return target(ShardIdentifier.DEFAULT, "default", dataSourceConfig);
    }

    /**
     * Creates a target for a shard datasource.
     *
     * @param shard shard identifier.
     * @param dataSourceConfig target datasource.
     * @return a migration target.
     */
    public static Target target(ShardIdentifier shard, DataSourceConfig dataSourceConfig) {
        return target(shard, "shard-" + shard.group + "-" + shard.member, dataSourceConfig);
    }

    /**
     * Creates a target for a named shard datasource.
     *
     * @param shard shard identifier.
     * @param name human-readable target name used in results/logging.
     * @param dataSourceConfig target datasource.
     * @return a migration target.
     */
    public static Target target(ShardIdentifier shard, String name, DataSourceConfig dataSourceConfig) {
        return new Target(shard, name, dataSourceConfig);
    }

    /** {@return a builder for advanced configuration} */
    public static Builder builder() {
        return new Builder();
    }

    private static MigrateResult migrateOne(
            String jdbcUrl,
            String username,
            String password,
            String[] locations,
            Consumer<FluentConfiguration> customizer) {
        requireNotBlank(jdbcUrl, "jdbcUrl is required");
        requireNotBlank(username, "username is required");
        Objects.requireNonNull(password, "password is required");
        var cfg = Flyway.configure().dataSource(jdbcUrl, username, password).locations(locations);
        if (NativeImageFlywayResourceProvider.inNativeImage()) {
            cfg.resourceProvider(new NativeImageFlywayResourceProvider(
                    cfg.getLocations(), Thread.currentThread().getContextClassLoader(), StandardCharsets.UTF_8));
        }
        customizer.accept(cfg);
        return cfg.load().migrate();
    }

    private static String[] normalizeLocations(String[] locations) {
        if (locations == null || locations.length == 0) {
            return new String[] {DEFAULT_LOCATION};
        }
        var out = new String[locations.length];
        for (int i = 0; i < locations.length; i++) {
            out[i] = requireNotBlank(locations[i], "locations cannot contain blank values");
        }
        return out;
    }

    private static String requireNotBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    /**
     * One database target to migrate.
     *
     * @param shard the shard identifier this datasource represents.
     * @param name a human-readable target name used in result reporting.
     * @param dataSourceConfig the datasource configuration to migrate.
     */
    public record Target(ShardIdentifier shard, String name, DataSourceConfig dataSourceConfig) {

        /** Creates a validated target. */
        public Target {
            Objects.requireNonNull(shard, "shard is required");
            name = requireNotBlank(name, "name is required");
            Objects.requireNonNull(dataSourceConfig, "dataSourceConfig is required");
        }
    }

    /**
     * Flyway result associated with the shard target that produced it.
     *
     * @param target the migrated target.
     * @param migrateResult Flyway's migration result for that target.
     */
    public record Result(Target target, MigrateResult migrateResult) {

        /** Creates a validated result. */
        public Result {
            Objects.requireNonNull(target, "target is required");
            Objects.requireNonNull(migrateResult, "migrateResult is required");
        }
    }

    /** Builder for migrations that need explicit targets or Flyway customization. */
    public static final class Builder {

        private final List<Target> targets = new ArrayList<>();
        private String[] locations = new String[] {DEFAULT_LOCATION};
        private Consumer<FluentConfiguration> customizer = cfg -> {};

        private Builder() {}

        /**
         * Adds one default-shard datasource target.
         *
         * @param dataSourceConfig target datasource.
         * @return this builder.
         */
        public Builder withDataSource(DataSourceConfig dataSourceConfig) {
            return withTarget(target(dataSourceConfig));
        }

        /**
         * Adds every primary shard from {@code shardingConfig}.
         *
         * @param shardingConfig the Ekbatan sharding config.
         * @return this builder.
         */
        public Builder withShardingConfig(ShardingConfig shardingConfig) {
            return withTargets(targets(shardingConfig));
        }

        /**
         * Adds one explicit target.
         *
         * @param target target to migrate.
         * @return this builder.
         */
        public Builder withTarget(Target target) {
            targets.add(Objects.requireNonNull(target, "target is required"));
            return this;
        }

        /**
         * Adds explicit targets.
         *
         * @param targets targets to migrate.
         * @return this builder.
         */
        public Builder withTargets(Target... targets) {
            Objects.requireNonNull(targets, "targets is required");
            for (var target : targets) {
                withTarget(target);
            }
            return this;
        }

        /**
         * Adds explicit targets.
         *
         * @param targets targets to migrate.
         * @return this builder.
         */
        public Builder withTargets(Collection<Target> targets) {
            Objects.requireNonNull(targets, "targets is required");
            targets.forEach(this::withTarget);
            return this;
        }

        /**
         * Replaces Flyway migration locations.
         *
         * @param locations Flyway locations; defaults to {@link #DEFAULT_LOCATION} when empty.
         * @return this builder.
         */
        public Builder locations(String... locations) {
            this.locations = normalizeLocations(locations);
            return this;
        }

        /**
         * Customizes Flyway configuration before {@code load().migrate()}.
         *
         * <p>The native-image resource provider is installed before this customizer runs, so advanced
         * callers can still override Flyway's resource provider explicitly.
         *
         * @param customizer Flyway configuration customizer.
         * @return this builder.
         */
        public Builder customize(Consumer<FluentConfiguration> customizer) {
            this.customizer = this.customizer.andThen(Objects.requireNonNull(customizer, "customizer is required"));
            return this;
        }

        /**
         * Runs migrations sequentially, failing fast on the first Flyway failure.
         *
         * @return one result per migrated target.
         */
        public List<Result> migrate() {
            if (targets.isEmpty()) {
                throw new IllegalStateException("at least one Flyway migration target is required");
            }
            List<Result> results = new ArrayList<>();
            for (var target : targets) {
                var dataSource = target.dataSourceConfig();
                var result =
                        migrateOne(dataSource.jdbcUrl, dataSource.username, dataSource.password, locations, customizer);
                results.add(new Result(target, result));
            }
            return List.copyOf(results);
        }
    }
}
