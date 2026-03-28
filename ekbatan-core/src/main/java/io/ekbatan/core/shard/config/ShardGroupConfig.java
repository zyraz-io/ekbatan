package io.ekbatan.core.shard.config;

import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.Validate;

public final class ShardGroupConfig {

    public final int group;
    public final String name;
    public final List<ShardMemberConfig> members;

    private ShardGroupConfig(Builder builder) {
        this.group = builder.group;
        this.name = Validate.notBlank(builder.name, "name is required");
        Validate.isTrue(!builder.members.isEmpty(), "at least one member is required");
        this.members = List.copyOf(builder.members);
    }

    public static final class Builder {

        private int group;
        private String name;
        private List<ShardMemberConfig> members = new ArrayList<>();

        private Builder() {}

        public static Builder shardGroupConfig() {
            return new Builder();
        }

        public Builder group(int group) {
            this.group = group;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder members(List<ShardMemberConfig> members) {
            this.members = new ArrayList<>(members);
            return this;
        }

        public Builder withMember(ShardMemberConfig member) {
            this.members.add(member);
            return this;
        }

        public ShardGroupConfig build() {
            return new ShardGroupConfig(this);
        }
    }
}
