package io.ekbatan.core.shard.config;

import io.ekbatan.core.config.DataSourceConfig;
import java.util.Optional;
import org.apache.commons.lang3.Validate;

public final class ShardMemberConfig {

    public final int member;
    public final Optional<String> name;
    public final DataSourceConfig primaryConfig;
    public final DataSourceConfig secondaryConfig;

    private ShardMemberConfig(Builder builder) {
        this.member = builder.member;
        this.name = builder.name;
        this.primaryConfig = Validate.notNull(builder.primaryConfig, "primaryConfig is required");
        this.secondaryConfig = Validate.notNull(builder.secondaryConfig, "secondaryConfig is required");
    }

    public static final class Builder {

        private int member;
        private Optional<String> name = Optional.empty();
        private DataSourceConfig primaryConfig;
        private DataSourceConfig secondaryConfig;

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

        public Builder primaryConfig(DataSourceConfig primaryConfig) {
            this.primaryConfig = primaryConfig;
            return this;
        }

        public Builder secondaryConfig(DataSourceConfig secondaryConfig) {
            this.secondaryConfig = secondaryConfig;
            return this;
        }

        public ShardMemberConfig build() {
            return new ShardMemberConfig(this);
        }
    }
}
