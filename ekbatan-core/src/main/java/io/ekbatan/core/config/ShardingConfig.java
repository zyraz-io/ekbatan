package io.ekbatan.core.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.ekbatan.core.shard.ShardIdentifier;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.Validate;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

/**
 * Top-level config describing all shards the application talks to. Built directly from
 * {@code ekbatan.sharding.*} keys in {@code application.yml} (Spring / Quarkus / Micronaut
 * autoconfiguration deserialises this shape) and consumed by
 * {@link io.ekbatan.core.shard.DatabaseRegistry#fromConfig(ShardingConfig)} to produce a
 * fully wired {@link io.ekbatan.core.shard.DatabaseRegistry}.
 *
 * <p>Hierarchy: a {@code ShardingConfig} contains one or more {@link ShardGroupConfig}s;
 * each group contains one or more {@code ShardMemberConfig}s (the individual databases);
 * each member carries one or more {@code DataSourceConfig}s (primary + optional secondary
 * read-replica).
 */
@JsonDeserialize(builder = ShardingConfig.Builder.class)
public final class ShardingConfig {

    /** Shard the framework routes to when no strategy commits to a specific shard. */
    public final ShardIdentifier defaultShard;

    /** All shard groups in the deployment; at least one is required. */
    public final List<ShardGroupConfig> groups;

    private ShardingConfig(Builder builder) {
        this.defaultShard = Validate.notNull(builder.defaultShard, "defaultShard is required");
        Validate.isTrue(!builder.groups.isEmpty(), "at least one group is required");
        this.groups = List.copyOf(builder.groups);
    }

    /** Fluent builder for {@link ShardingConfig}. Obtain via {@link #shardingConfig()}. */
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {

        private ShardIdentifier defaultShard;
        private List<ShardGroupConfig> groups = new ArrayList<>();

        private Builder() {}

        /** {@return a fresh builder for {@link ShardingConfig}} */
        public static Builder shardingConfig() {
            return new Builder();
        }

        /**
         * Sets the default shard. Required.
         *
         * @param defaultShard the shard to route to when no strategy commits.
         * @return this builder, for chaining.
         */
        public Builder defaultShard(ShardIdentifier defaultShard) {
            this.defaultShard = defaultShard;
            return this;
        }

        /**
         * Replaces the entire groups list.
         *
         * @param groups the new groups list.
         * @return this builder, for chaining.
         */
        public Builder groups(List<ShardGroupConfig> groups) {
            this.groups = new ArrayList<>(groups);
            return this;
        }

        /**
         * Adds a single group.
         *
         * @param group the group to add.
         * @return this builder, for chaining.
         */
        @JsonIgnore
        public Builder withGroup(ShardGroupConfig group) {
            this.groups.add(group);
            return this;
        }

        /** {@return a configured {@link ShardingConfig}} */
        public ShardingConfig build() {
            return new ShardingConfig(this);
        }
    }
}
