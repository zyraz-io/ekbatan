package io.ekbatan.micronaut;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.action.ActionRegistry;
import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.config.jackson.EkbatanConfigJacksonModule;
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
import java.util.Optional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Micronaut {@code @Factory} class for Ekbatan's core surface. Binds {@code ekbatan.sharding.*}
 * config to {@link ShardingConfig} (via the Jackson hybrid path), wires the
 * {@link DatabaseRegistry}, and produces the {@link ActionRegistry} / {@link RepositoryRegistry}
 * / {@link ActionExecutor} chain.
 *
 * <p>User-supplied {@code @EkbatanAction} / {@code @EkbatanRepository} beans are lifted to
 * Micronaut {@code @Singleton}s at compile time by the {@code EkbatanStereotypeVisitor}
 * (a {@code TypeElementVisitor} shipped on the AP path via the {@code ekbatan-micronaut} jar),
 * then injected here via {@code List<Action<?, ?>>} / {@code List<AbstractRepository<?, ?, ?, ?>>}.
 */
@Factory
public class EkbatanCoreConfiguration {

    /** Required by Micronaut; the container instantiates this {@code @Factory} class to invoke its {@code @Bean} methods. */
    public EkbatanCoreConfiguration() {}

    /** {@return the Ekbatan-specific Jackson module that maps the framework's builder-based config types} */
    @Bean
    @Singleton
    public EkbatanConfigJacksonModule ekbatanConfigJacksonModule() {
        return new EkbatanConfigJacksonModule();
    }

    /**
     * Binds the {@code ekbatan.sharding} subtree from Micronaut's {@link Environment} via the
     * Jackson hybrid path: an internal {@code ConfigTreeBuilder} reconstructs the nested tree
     * from flat keys, then a private {@link JsonMapper} (with the Ekbatan mix-in module)
     * deserializes it into {@link ShardingConfig}.
     *
     * @param environment Micronaut's environment, source of the {@code ekbatan.sharding.*} keys.
     * @param module the Ekbatan Jackson module produced by {@link #ekbatanConfigJacksonModule}.
     * @return the parsed {@link ShardingConfig} for the running application.
     */
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

    /**
     * Builds the {@link DatabaseRegistry} that owns connection pools for every shard member.
     *
     * @param shardingConfig the sharding topology resolved from configuration.
     * @return a registry that opens pools eagerly; Micronaut closes it via {@code preDestroy="close"}.
     */
    @Bean(preDestroy = "close")
    @Singleton
    public DatabaseRegistry ekbatanDatabaseRegistry(ShardingConfig shardingConfig) {
        return DatabaseRegistry.fromConfig(shardingConfig);
    }

    /** {@return the framework's default UTC {@link Clock}; override with your own {@code @Bean Clock} for tests} */
    @Bean
    @Singleton
    public Clock ekbatanClock() {
        return Clock.systemUTC();
    }

    /** {@return the application-facing Jackson 3 {@link JsonMapper} used for event payload serialization} */
    @Bean
    @Singleton
    public JsonMapper ekbatanJsonMapper() {
        return JsonMapper.builder().build();
    }

    /**
     * Collects every {@code @EkbatanRepository}-annotated bean discovered by the compile-time
     * {@code EkbatanStereotypeVisitor} and bundles them into a single {@link RepositoryRegistry}.
     *
     * @param repositories the application's repository beans, injected by Micronaut.
     * @return the registry consulted by actions and tests for repository lookup.
     */
    @Bean
    @Singleton
    public RepositoryRegistry ekbatanRepositoryRegistry(List<AbstractRepository<?, ?, ?, ?>> repositories) {
        return RepositoryRegistry.Builder.repositoryRegistry()
                .withRepositories(repositories)
                .build();
    }

    /**
     * Collects every {@code @EkbatanAction}-annotated bean discovered by the compile-time
     * {@code EkbatanStereotypeVisitor} and bundles them into a single {@link ActionRegistry}.
     *
     * <p>Singleton-scoped Actions: per-call mutable state on {@code Action.plan} is bound by the
     * framework via {@code Action.runIn(...)} using a {@code ScopedValue}, so a single instance
     * is safe across concurrent {@code ActionExecutor.execute(...)} calls.
     *
     * @param actions the application's action beans, injected by Micronaut.
     * @return the registry consulted by the {@link ActionExecutor} during dispatch.
     */
    @Bean
    @Singleton
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ActionRegistry ekbatanActionRegistry(List<Action<?, ?>> actions) {
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
     * @param properties the Ekbatan runtime configuration (namespace, jobs, local event handler).
     * @param databaseRegistry per-shard connection pools.
     * @param actionRegistry the registry of {@code @EkbatanAction} beans.
     * @param repositoryRegistry the registry of {@code @EkbatanRepository} beans.
     * @param jsonMapper the Jackson mapper used for event payload serialization.
     * @param clock the system clock used for event timestamps.
     * @param eventPersister an optional user-supplied event persister.
     * @return the configured {@link ActionExecutor}.
     */
    @Bean
    @Singleton
    public ActionExecutor ekbatanActionExecutor(
            EkbatanProperties properties,
            DatabaseRegistry databaseRegistry,
            ActionRegistry actionRegistry,
            RepositoryRegistry repositoryRegistry,
            JsonMapper jsonMapper,
            Clock clock,
            Optional<EventPersister> eventPersister) {
        var builder = ActionExecutor.Builder.actionExecutor()
                .namespace(properties.getNamespace())
                .databaseRegistry(databaseRegistry)
                .actionRegistry(actionRegistry)
                .repositoryRegistry(repositoryRegistry)
                .objectMapper(jsonMapper)
                .clock(clock);
        // Optional override: an application can supply its own EventPersister bean (e.g. one that
        // encrypts payloads, writes to a separate sink, or uses a different table layout).
        // Otherwise the builder falls back to its built-in SingleTableJsonEventPersister default.
        eventPersister.ifPresent(builder::eventPersister);
        return builder.build();
    }
}
