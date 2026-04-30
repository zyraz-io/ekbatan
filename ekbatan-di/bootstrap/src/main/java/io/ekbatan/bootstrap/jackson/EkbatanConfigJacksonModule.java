package io.ekbatan.bootstrap.jackson;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.core.shard.config.ShardGroupConfig;
import io.ekbatan.core.shard.config.ShardMemberConfig;
import io.ekbatan.core.shard.config.ShardingConfig;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;
import tools.jackson.databind.module.SimpleModule;

/**
 * Jackson mix-in module that teaches an {@code ObjectMapper} how to deserialize Ekbatan's
 * sharding config tree. The four config classes follow a private-constructor + Builder-only
 * pattern (validation lives in the private constructor invoked by {@code Builder.build()}); the
 * mix-ins point Jackson at each Builder via {@code @JsonDeserialize(builder = X.Builder.class)}
 * and {@code @JsonPOJOBuilder(buildMethodName = "build", withPrefix = "")} so YAML keys map
 * directly to fluent setters. {@link ShardIdentifier} (no Builder) uses a {@code @JsonCreator}
 * mix-in pointing at its static factory.
 */
public final class EkbatanConfigJacksonModule extends SimpleModule {

    public EkbatanConfigJacksonModule() {
        super("ekbatan-config");

        setMixInAnnotation(DataSourceConfig.class, DataSourceConfigMixin.class);
        setMixInAnnotation(DataSourceConfig.Builder.class, DataSourceConfigBuilderMixin.class);

        setMixInAnnotation(ShardingConfig.class, ShardingConfigMixin.class);
        setMixInAnnotation(ShardingConfig.Builder.class, ShardingConfigBuilderMixin.class);

        setMixInAnnotation(ShardGroupConfig.class, ShardGroupConfigMixin.class);
        setMixInAnnotation(ShardGroupConfig.Builder.class, ShardGroupConfigBuilderMixin.class);

        setMixInAnnotation(ShardMemberConfig.class, ShardMemberConfigMixin.class);
        setMixInAnnotation(ShardMemberConfig.Builder.class, ShardMemberConfigBuilderMixin.class);

        setMixInAnnotation(ShardIdentifier.class, ShardIdentifierMixin.class);
    }

    @JsonDeserialize(builder = DataSourceConfig.Builder.class)
    abstract static class DataSourceConfigMixin {}

    @JsonPOJOBuilder(buildMethodName = "build", withPrefix = "")
    abstract static class DataSourceConfigBuilderMixin {}

    @JsonDeserialize(builder = ShardingConfig.Builder.class)
    abstract static class ShardingConfigMixin {}

    @JsonPOJOBuilder(buildMethodName = "build", withPrefix = "")
    abstract static class ShardingConfigBuilderMixin {
        // Hide the singular variant so Jackson doesn't expose "withGroup" alongside "groups".
        @JsonIgnore
        public abstract Object withGroup(Object group);
    }

    @JsonDeserialize(builder = ShardGroupConfig.Builder.class)
    abstract static class ShardGroupConfigMixin {}

    @JsonPOJOBuilder(buildMethodName = "build", withPrefix = "")
    abstract static class ShardGroupConfigBuilderMixin {
        @JsonIgnore
        public abstract Object withMember(Object member);
    }

    @JsonDeserialize(builder = ShardMemberConfig.Builder.class)
    abstract static class ShardMemberConfigMixin {}

    @JsonPOJOBuilder(buildMethodName = "build", withPrefix = "")
    abstract static class ShardMemberConfigBuilderMixin {
        // Hide typed shortcuts and the per-entry adder; the canonical YAML form is a "configs"
        // map with reserved keys "primaryConfig"/"secondaryConfig" plus user-defined names.
        @JsonIgnore
        public abstract Object primaryConfig(Object primary);

        @JsonIgnore
        public abstract Object secondaryConfig(Object secondary);

        @JsonIgnore
        public abstract Object withConfig(String name, Object config);
    }

    abstract static class ShardIdentifierMixin {
        @JsonCreator
        public static ShardIdentifier of(@JsonProperty("group") int group, @JsonProperty("member") int member) {
            throw new UnsupportedOperationException("mix-in body — never invoked");
        }
    }
}
