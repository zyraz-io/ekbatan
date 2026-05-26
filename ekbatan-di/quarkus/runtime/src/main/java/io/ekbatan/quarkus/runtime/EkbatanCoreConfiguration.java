package io.ekbatan.quarkus.runtime;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.action.ActionRegistry;
import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.config.jackson.EkbatanConfigJacksonModule;
import io.ekbatan.core.repository.AbstractRepository;
import io.ekbatan.core.repository.RepositoryRegistry;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.config.ShardingConfig;
import io.quarkus.arc.All;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.List;
import org.eclipse.microprofile.config.ConfigProvider;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Quarkus runtime CDI producer class for Ekbatan's core surface. Produces the
 * {@link ShardingConfig}, {@link DatabaseRegistry}, {@link ActionRegistry},
 * {@link RepositoryRegistry}, and {@link ActionExecutor} beans that the extension's
 * deployment processor registers in the bean container.
 *
 * <p>Discovered at deployment time via the Quarkus extension processor
 * (see {@code io.ekbatan.quarkus.deployment.EkbatanProcessor}) and constructed at runtime
 * by Arc. User-supplied {@code @EkbatanAction} / {@code @EkbatanRepository} beans are
 * picked up by the deployment processor's index walk and injected here via {@code @All}.
 */
@Singleton
public class EkbatanCoreConfiguration {

    /** Required by CDI; the container instantiates this bean class to invoke its producer methods. */
    public EkbatanCoreConfiguration() {}

    /**
     * Produces the Jackson module that knows how to deserialize Ekbatan's {@code DataSourceConfig}
     * builder-based hierarchy into a {@link ShardingConfig}.
     *
     * @return the Ekbatan-specific Jackson module, registered with {@link #ekbatanShardingConfig}'s
     *     private {@link JsonMapper}.
     */
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
     * <p>{@code @ConfigMapping} can't construct {@code DataSourceConfig} entries directly - they
     * have private constructors + Builders, and the {@code configs} map uses user-defined keys.
     *
     * @param module the Ekbatan Jackson module produced by {@link #ekbatanConfigJacksonModule}.
     * @return the parsed {@link ShardingConfig} for the running application.
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

    /**
     * Builds the {@link DatabaseRegistry} that owns connection pools for every shard member.
     *
     * @param shardingConfig the sharding topology resolved from configuration.
     * @return a registry that opens pools eagerly and is closed by {@link #closeDatabaseRegistry}.
     */
    @Produces
    @Singleton
    public DatabaseRegistry ekbatanDatabaseRegistry(ShardingConfig shardingConfig) {
        return DatabaseRegistry.fromConfig(shardingConfig);
    }

    /**
     * CDI disposer for {@link #ekbatanDatabaseRegistry} - drains connection pools at shutdown.
     *
     * @param registry the registry being disposed.
     * @throws Exception if pool teardown fails.
     */
    public void closeDatabaseRegistry(@Disposes DatabaseRegistry registry) throws Exception {
        registry.close();
    }

    /**
     * Produces the framework's default UTC {@link Clock}. Override by defining your own
     * {@code @Produces Clock} bean (useful for tests that need a test-support {@code VirtualClock}).
     *
     * @return a system UTC clock.
     */
    @Produces
    @Singleton
    public Clock ekbatanClock() {
        return Clock.systemUTC();
    }

    /**
     * Produces the application-facing {@link JsonMapper} used by the {@link ActionExecutor}
     * to serialize event payloads to the eventlog.
     *
     * @return a default Jackson 3 {@link JsonMapper}.
     */
    @Produces
    @Singleton
    public JsonMapper ekbatanJsonMapper() {
        return JsonMapper.builder().build();
    }

    /**
     * Collects every {@code @EkbatanRepository}-annotated bean discovered by the Quarkus extension
     * deployment processor and bundles them into a single {@link RepositoryRegistry}.
     *
     * @param repositories the application's repository beans, injected via Arc {@code @All}.
     * @return the registry consulted by actions and tests for repository lookup.
     */
    @Produces
    @Singleton
    public RepositoryRegistry ekbatanRepositoryRegistry(@All List<AbstractRepository<?, ?, ?, ?>> repositories) {
        return RepositoryRegistry.Builder.repositoryRegistry()
                .withRepositories(repositories)
                .build();
    }

    /**
     * Collects every {@code @EkbatanAction}-annotated bean discovered by the Quarkus extension
     * deployment processor and bundles them into a single {@link ActionRegistry}.
     *
     * <p>Singleton-scoped Actions: per-call mutable state on Action.plan is bound by the framework
     * via Action.runIn(plan, principal, params) using a ScopedValue, so a single instance is safe
     * across concurrent {@link ActionExecutor#execute} calls.
     *
     * @param actions the application's action beans, injected via Arc {@code @All}.
     * @return the registry consulted by the {@link ActionExecutor} during dispatch.
     */
    @Produces
    @Singleton
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ActionRegistry ekbatanActionRegistry(@All List<Action<?, ?>> actions) {
        return (ActionRegistry) ActionRegistry.Builder.actionRegistry()
                .withActions((List) actions)
                .build();
    }

    /**
     * Wires the central {@link ActionExecutor} from its required collaborators. If the application
     * defines its own {@link EventPersister} bean (e.g. one that encrypts payloads or uses a custom
     * table layout), it's injected here; otherwise the builder falls back to its built-in
     * single-table JSON default.
     *
     * @param config the Ekbatan runtime configuration (namespace, jobs, local event handler).
     * @param databaseRegistry per-shard connection pools.
     * @param actionRegistry the registry of {@code @EkbatanAction} beans.
     * @param repositoryRegistry the registry of {@code @EkbatanRepository} beans.
     * @param jsonMapper the Jackson mapper used for event payload serialization.
     * @param clock the system clock used for event timestamps.
     * @param eventPersisterInstance an Arc {@link Instance} that may resolve a user-supplied event persister.
     * @return the configured {@link ActionExecutor}.
     */
    @Produces
    @Singleton
    public ActionExecutor ekbatanActionExecutor(
            EkbatanProperties config,
            DatabaseRegistry databaseRegistry,
            ActionRegistry actionRegistry,
            RepositoryRegistry repositoryRegistry,
            JsonMapper jsonMapper,
            Clock clock,
            Instance<EventPersister> eventPersisterInstance) {
        var builder = ActionExecutor.Builder.actionExecutor()
                .namespace(config.namespace())
                .databaseRegistry(databaseRegistry)
                .actionRegistry(actionRegistry)
                .repositoryRegistry(repositoryRegistry)
                .objectMapper(jsonMapper)
                .clock(clock);
        // Optional override: an application can supply its own EventPersister bean (e.g. one that
        // encrypts payloads, writes to a separate sink, or uses a different table layout).
        // Otherwise the builder falls back to its built-in SingleTableJsonEventPersister default.
        if (eventPersisterInstance.isResolvable()) {
            builder.eventPersister(eventPersisterInstance.get());
        }
        return builder.build();
    }
}
