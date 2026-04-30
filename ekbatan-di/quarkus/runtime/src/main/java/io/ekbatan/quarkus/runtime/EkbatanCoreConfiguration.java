package io.ekbatan.quarkus.runtime;

import io.ekbatan.bootstrap.RegistryAssembler;
import io.ekbatan.bootstrap.jackson.EkbatanConfigJacksonModule;
import io.ekbatan.core.action.Action;
import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.action.ActionRegistry;
import io.ekbatan.core.repository.AbstractRepository;
import io.ekbatan.core.repository.RepositoryRegistry;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.config.ShardingConfig;
import io.quarkus.arc.All;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.List;
import org.eclipse.microprofile.config.ConfigProvider;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Singleton
public class EkbatanCoreConfiguration {

    @Produces
    @Singleton
    public EkbatanConfigJacksonModule ekbatanConfigJacksonModule() {
        return new EkbatanConfigJacksonModule();
    }

    /**
     * Binds the {@code ekbatan.sharding} subtree from SmallRye Config via the Jackson hybrid path:
     * {@link ConfigTreeBuilder} reconstructs the nested tree from flat keys, then a private
     * {@link JsonMapper} (with the Ekbatan mix-in module) deserializes it into {@link ShardingConfig}.
     *
     * <p>{@code @ConfigMapping} can't construct {@code DataSourceConfig} entries directly — they
     * have private constructors + Builders, and the {@code configs} map uses user-defined keys.
     */
    @Produces
    @Singleton
    public ShardingConfig ekbatanShardingConfig(EkbatanConfigJacksonModule module) {
        var config = ConfigProvider.getConfig();
        var tree = ConfigTreeBuilder.readSubtree(config, "ekbatan.sharding");
        if (tree.isEmpty()) {
            throw new IllegalStateException(
                    "Ekbatan requires 'ekbatan.sharding' to be configured (groups[].members[].configs.primaryConfig.*). "
                            + "Either populate it in application.yaml/application.properties or define a ShardingConfig @Produces of your own.");
        }
        // Private mapper: FAIL_ON_UNKNOWN_PROPERTIES surfaces typos at startup without leaking
        // strictness into any application-level Jackson configuration.
        var mapper = JsonMapper.builder()
                .addModule(module)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        return mapper.convertValue(tree, ShardingConfig.class);
    }

    @Produces
    @Singleton
    public DatabaseRegistry ekbatanDatabaseRegistry(ShardingConfig shardingConfig) {
        return DatabaseRegistry.fromConfig(shardingConfig);
    }

    public void closeDatabaseRegistry(@Disposes DatabaseRegistry registry) throws Exception {
        registry.close();
    }

    @Produces
    @Singleton
    public Clock ekbatanClock() {
        return Clock.systemUTC();
    }

    @Produces
    @Singleton
    public JsonMapper ekbatanJsonMapper() {
        return JsonMapper.builder().build();
    }

    @Produces
    @Singleton
    public RepositoryRegistry ekbatanRepositoryRegistry(@All List<AbstractRepository<?, ?, ?, ?>> repositories) {
        return RegistryAssembler.repositoryRegistry(repositories);
    }

    // Singleton-scoped Actions: per-call mutable state on Action.plan is bound by the framework
    // via Action.runIn(plan, principal, params) using a ScopedValue, so a single instance is safe
    // across concurrent ActionExecutor.execute(...) calls.
    @Produces
    @Singleton
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ActionRegistry ekbatanActionRegistry(@All List<Action<?, ?>> actions) {
        return (ActionRegistry) RegistryAssembler.actionRegistry((List) actions);
    }

    @Produces
    @Singleton
    public ActionExecutor ekbatanActionExecutor(
            EkbatanProperties config,
            DatabaseRegistry databaseRegistry,
            ActionRegistry actionRegistry,
            RepositoryRegistry repositoryRegistry,
            JsonMapper jsonMapper,
            Clock clock) {
        return ActionExecutor.Builder.actionExecutor()
                .namespace(config.namespace())
                .databaseRegistry(databaseRegistry)
                .actionRegistry(actionRegistry)
                .repositoryRegistry(repositoryRegistry)
                .objectMapper(jsonMapper)
                .clock(clock)
                .build();
    }
}
