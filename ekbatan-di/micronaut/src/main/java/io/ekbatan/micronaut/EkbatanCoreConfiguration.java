package io.ekbatan.micronaut;

import io.ekbatan.bootstrap.RegistryAssembler;
import io.ekbatan.bootstrap.jackson.EkbatanConfigJacksonModule;
import io.ekbatan.core.action.Action;
import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.action.ActionRegistry;
import io.ekbatan.core.repository.AbstractRepository;
import io.ekbatan.core.repository.RepositoryRegistry;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.config.ShardingConfig;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Factory
public class EkbatanCoreConfiguration {

    @Bean
    @Singleton
    public EkbatanConfigJacksonModule ekbatanConfigJacksonModule() {
        return new EkbatanConfigJacksonModule();
    }

    @Bean
    @Singleton
    public ShardingConfig ekbatanShardingConfig(Environment environment, EkbatanConfigJacksonModule module) {
        var tree = ConfigTreeBuilder.readSubtree(environment, "ekbatan.sharding");
        if (tree.isEmpty()) {
            throw new IllegalStateException(
                    "Ekbatan requires 'ekbatan.sharding' to be configured (groups[].members[].configs.primaryConfig.*). "
                            + "Either populate it in application.yml/application.properties or define a ShardingConfig "
                            + "@Bean of your own.");
        }
        // Private mapper: FAIL_ON_UNKNOWN_PROPERTIES surfaces typos at startup without leaking
        // strictness into any application-level Jackson configuration.
        var mapper = JsonMapper.builder()
                .addModule(module)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        return mapper.convertValue(tree, ShardingConfig.class);
    }

    @Bean(preDestroy = "close")
    @Singleton
    public DatabaseRegistry ekbatanDatabaseRegistry(ShardingConfig shardingConfig) {
        return DatabaseRegistry.fromConfig(shardingConfig);
    }

    @Bean
    @Singleton
    public Clock ekbatanClock() {
        return Clock.systemUTC();
    }

    @Bean
    @Singleton
    public JsonMapper ekbatanJsonMapper() {
        return JsonMapper.builder().build();
    }

    @Bean
    @Singleton
    public RepositoryRegistry ekbatanRepositoryRegistry(List<AbstractRepository<?, ?, ?, ?>> repositories) {
        return RegistryAssembler.repositoryRegistry(repositories);
    }

    // Singleton-scoped Actions: per-call mutable state on Action.plan is bound by the framework
    // via Action.runIn(...) using a ScopedValue, so a single instance is safe across concurrent
    // ActionExecutor.execute(...) calls. Stereotype discovery is driven at compile time by
    // EkbatanStereotypeVisitor.
    @Bean
    @Singleton
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ActionRegistry ekbatanActionRegistry(List<Action<?, ?>> actions) {
        return (ActionRegistry) RegistryAssembler.actionRegistry((List) actions);
    }

    @Bean
    @Singleton
    public ActionExecutor ekbatanActionExecutor(
            EkbatanProperties properties,
            DatabaseRegistry databaseRegistry,
            ActionRegistry actionRegistry,
            RepositoryRegistry repositoryRegistry,
            JsonMapper jsonMapper,
            Clock clock) {
        return ActionExecutor.Builder.actionExecutor()
                .namespace(properties.getNamespace())
                .databaseRegistry(databaseRegistry)
                .actionRegistry(actionRegistry)
                .repositoryRegistry(repositoryRegistry)
                .objectMapper(jsonMapper)
                .clock(clock)
                .build();
    }
}
