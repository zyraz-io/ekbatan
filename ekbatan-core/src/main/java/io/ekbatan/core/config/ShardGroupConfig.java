package io.ekbatan.core.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.ekbatan.core.internal.Validate;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

/**
 * Mid-level config describing one group within a {@link ShardingConfig} - a logical cluster
 * of physical databases that share a routing concern (e.g. "all US-region wallets,"
 * "free-tier tenants"). Each group has a numeric {@code group} key that pairs with a member
 * key to form a {@link io.ekbatan.core.shard.ShardIdentifier}.
 */
@JsonDeserialize(builder = ShardGroupConfig.Builder.class)
public final class ShardGroupConfig {

    /** Numeric key for this group; pairs with each member's key to form a {@link io.ekbatan.core.shard.ShardIdentifier}. */
    public final int group;

    /** Human-readable name for this group (e.g. {@code "us-east"}). */
    public final String name;

    /** Members (individual databases) in this group; at least one is required. */
    public final List<ShardMemberConfig> members;

    private ShardGroupConfig(Builder builder) {
        this.group = builder.group;
        this.name = Validate.notBlank(builder.name, "name is required");
        Validate.isTrue(!builder.members.isEmpty(), "at least one member is required");
        this.members = List.copyOf(builder.members);
    }

    /** Fluent builder for {@link ShardGroupConfig}. Obtain via {@link #shardGroupConfig()}. */
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {

        private int group;
        private String name;
        private List<ShardMemberConfig> members = new ArrayList<>();

        private Builder() {}

        /** {@return a fresh builder for {@link ShardGroupConfig}} */
        public static Builder shardGroupConfig() {
            return new Builder();
        }

        /**
         * Sets the group's numeric key.
         *
         * @param group the group key.
         * @return this builder, for chaining.
         */
        public Builder group(int group) {
            this.group = group;
            return this;
        }

        /**
         * Sets the group's human-readable name.
         *
         * @param name the name.
         * @return this builder, for chaining.
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Replaces the entire members list.
         *
         * @param members the new members list.
         * @return this builder, for chaining.
         */
        public Builder members(List<ShardMemberConfig> members) {
            this.members = new ArrayList<>(members);
            return this;
        }

        /**
         * Adds a single member to the group.
         *
         * @param member the member to add.
         * @return this builder, for chaining.
         */
        @JsonIgnore
        public Builder withMember(ShardMemberConfig member) {
            this.members.add(member);
            return this;
        }

        /** {@return a configured {@link ShardGroupConfig}} */
        public ShardGroupConfig build() {
            return new ShardGroupConfig(this);
        }
    }
}
