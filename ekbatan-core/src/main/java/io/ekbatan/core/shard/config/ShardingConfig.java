package io.ekbatan.core.shard.config;

import io.ekbatan.core.shard.ShardIdentifier;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.Validate;

public final class ShardingConfig {

    public final ShardIdentifier defaultShard;
    public final List<ShardGroupConfig> groups;

    private ShardingConfig(Builder builder) {
        this.defaultShard = Validate.notNull(builder.defaultShard, "defaultShard is required");
        Validate.isTrue(!builder.groups.isEmpty(), "at least one group is required");
        this.groups = List.copyOf(builder.groups);
    }

    public static final class Builder {

        private ShardIdentifier defaultShard;
        private List<ShardGroupConfig> groups = new ArrayList<>();

        private Builder() {}

        public static Builder shardingConfig() {
            return new Builder();
        }

        public Builder defaultShard(ShardIdentifier defaultShard) {
            this.defaultShard = defaultShard;
            return this;
        }

        public Builder groups(List<ShardGroupConfig> groups) {
            this.groups = new ArrayList<>(groups);
            return this;
        }

        public Builder withGroup(ShardGroupConfig group) {
            this.groups.add(group);
            return this;
        }

        public ShardingConfig build() {
            return new ShardingConfig(this);
        }
    }
}
