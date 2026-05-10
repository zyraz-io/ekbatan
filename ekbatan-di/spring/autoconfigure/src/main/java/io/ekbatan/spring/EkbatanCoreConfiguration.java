package io.ekbatan.spring;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.action.ActionRegistry;
import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.config.jackson.EkbatanConfigJacksonModule;
import io.ekbatan.core.repository.AbstractRepository;
import io.ekbatan.core.repository.RepositoryRegistry;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.config.ShardingConfig;
import io.ekbatan.di.EkbatanAction;
import io.ekbatan.spring.internal.EkbatanActionsHolder;
import io.ekbatan.spring.internal.EkbatanStereotypeBeanRegistrar;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Boot auto-configuration for Ekbatan's core surface: binds {@code ekbatan.sharding.*}
 * config to {@link ShardingConfig}, wires the {@link DatabaseRegistry} (and its underlying
 * {@code TransactionManager}s and Hikari pools), and produces the {@link ActionRegistry} /
 * {@link RepositoryRegistry} / {@link ActionExecutor} chain.
 *
 * <p>{@link EkbatanStereotypeBeanRegistrar} (imported here) scans the application's auto-
 * configuration packages for {@code @EkbatanAction} / {@code @EkbatanRepository} / etc.
 * stereotypes and registers them as Spring beans so they reach the registries above without
 * manual wiring.
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean} — users can override any one piece by
 * declaring their own. This is the canonical Spring Boot starter idiom.
 */
@AutoConfiguration
@EnableConfigurationProperties(EkbatanProperties.class)
@Import(EkbatanStereotypeBeanRegistrar.class)
public class EkbatanCoreConfiguration {

    /** Required by Spring; the container instantiates this auto-configuration class to invoke its {@code @Bean} methods. */
    public EkbatanCoreConfiguration() {}

    /** {@return the Ekbatan-specific Jackson module that maps the framework's builder-based config types} */
    @Bean
    @ConditionalOnMissingBean
    public EkbatanConfigJacksonModule ekbatanConfigJacksonModule() {
        return new EkbatanConfigJacksonModule();
    }

    /**
     * Binds the {@code ekbatan.sharding} subtree from Spring's {@link Environment} via the
     * Jackson hybrid path: Spring's {@link Binder} reconstructs the nested tree from flat
     * keys, then a private {@link JsonMapper} (with the Ekbatan mix-in module) deserializes
     * it into {@link ShardingConfig}.
     *
     * @param environment Spring's environment, source of the {@code ekbatan.sharding.*} keys.
     * @param module the Ekbatan Jackson module produced by {@link #ekbatanConfigJacksonModule}.
     * @return the parsed {@link ShardingConfig} for the running application.
     */
    @Bean
    @ConditionalOnMissingBean
    public ShardingConfig ekbatanShardingConfig(Environment environment, EkbatanConfigJacksonModule module) {
        var raw = Binder.get(environment)
                .bind("ekbatan.sharding", Bindable.mapOf(String.class, Object.class))
                .orElseThrow(() -> new IllegalStateException(
                        "Ekbatan requires 'ekbatan.sharding' to be configured (groups[].members[].configs.primaryConfig.*). "
                                + "Either populate it in application.yml or define a ShardingConfig @Bean."));

        // Spring's Binder represents YAML lists as integer-keyed maps; rebuild them as Lists so
        // Jackson's BuilderBasedDeserializer sees JSON arrays where it expects them.
        var normalized = ConfigTreeBuilder.normalize(raw);

        // Private mapper: feature flags here don't leak into the application-level JsonMapper.
        var mapper = JsonMapper.builder()
                .addModule(module)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        return mapper.convertValue(normalized, ShardingConfig.class);
    }

    /**
     * Builds the {@link DatabaseRegistry} that owns connection pools for every shard member.
     *
     * @param shardingConfig the sharding topology resolved from configuration.
     * @return a registry that opens pools eagerly; closed automatically by Spring at shutdown.
     */
    @Bean
    @ConditionalOnMissingBean
    public DatabaseRegistry ekbatanDatabaseRegistry(ShardingConfig shardingConfig) {
        return DatabaseRegistry.fromConfig(shardingConfig);
    }

    /** {@return the framework's default UTC {@link Clock}; override with your own {@code @Bean Clock} for tests} */
    @Bean
    @ConditionalOnMissingBean
    public Clock ekbatanClock() {
        return Clock.systemUTC();
    }

    /**
     * Collects every {@code @EkbatanRepository}-annotated bean discovered by
     * {@link EkbatanStereotypeBeanRegistrar} and bundles them into a single {@link RepositoryRegistry}.
     *
     * @param repositories the application's repository beans, injected by Spring.
     * @return the registry consulted by actions and tests for repository lookup.
     */
    @Bean
    @ConditionalOnMissingBean
    public RepositoryRegistry ekbatanRepositoryRegistry(List<AbstractRepository<?, ?, ?, ?>> repositories) {
        return RepositoryRegistry.Builder.repositoryRegistry()
                .withRepositories(repositories)
                .build();
    }

    /**
     * Builds the {@link ActionRegistry} from {@code @EkbatanAction}-annotated classes. Action
     * instances are framework-private singletons constructed via
     * {@link AutowireCapableBeanFactory#createBean(Class)} (not registered as Spring beans
     * directly) because per-call mutable state on {@code Action.plan} is bound via a
     * {@code ScopedValue} so a single instance is safely shared across concurrent
     * {@code execute(...)} calls.
     *
     * @param beanFactory the bean factory used to discover the AOT-collected or runtime-scanned action classes.
     * @param environment Spring's environment for the runtime-scan path.
     * @param autowireBeanFactory used to instantiate each action class with its dependencies wired.
     * @return the registry consulted by the {@link ActionExecutor} during dispatch.
     */
    @Bean
    @ConditionalOnMissingBean
    public ActionRegistry ekbatanActionRegistry(
            BeanFactory beanFactory, Environment environment, AutowireCapableBeanFactory autowireBeanFactory) {
        // createBean() instead of registering bean definitions: action instances are
        // framework-private (reachable only via ActionExecutor / ActionSpec) and per-call mutable
        // state on Action.plan is bound via runIn(...) using a ScopedValue, so a single instance
        // is safely shared across concurrent execute(...) calls.
        var actionClasses = scanActionClasses(beanFactory, environment);
        var actions = new java.util.ArrayList<Action<?, ?>>(actionClasses.size());
        for (var cls : actionClasses) {
            actions.add((Action<?, ?>) autowireBeanFactory.createBean(cls));
        }
        return ActionRegistry.Builder.actionRegistry().withActions(actions).build();
    }

    /**
     * Wires the central {@link ActionExecutor} from its required collaborators. If the
     * application defines its own {@link EventPersister} bean (e.g. one that encrypts payloads
     * or uses a custom table layout), it's injected here; otherwise the builder falls back to
     * its built-in single-table JSON default.
     *
     * @param properties the Ekbatan runtime configuration (namespace, jobs, local event handler).
     * @param databaseRegistry per-shard connection pools.
     * @param actionRegistry the registry of {@code @EkbatanAction} classes.
     * @param repositoryRegistry the registry of {@code @EkbatanRepository} beans.
     * @param objectMapper the Jackson mapper used for event payload serialization.
     * @param clock the system clock used for event timestamps.
     * @param eventPersisterProvider an Spring {@link ObjectProvider} that may resolve a user-supplied event persister.
     * @return the configured {@link ActionExecutor}.
     */
    @Bean
    @ConditionalOnMissingBean
    public ActionExecutor ekbatanActionExecutor(
            EkbatanProperties properties,
            DatabaseRegistry databaseRegistry,
            ActionRegistry actionRegistry,
            RepositoryRegistry repositoryRegistry,
            ObjectMapper objectMapper,
            Clock clock,
            ObjectProvider<EventPersister> eventPersisterProvider) {
        var builder = ActionExecutor.Builder.actionExecutor()
                .namespace(properties.namespace())
                .databaseRegistry(databaseRegistry)
                .actionRegistry(actionRegistry)
                .repositoryRegistry(repositoryRegistry)
                .objectMapper(objectMapper)
                .clock(clock);
        // Optional override: an application can supply its own EventPersister bean (e.g. one that
        // encrypts payloads, writes to a separate sink, or uses a different table layout).
        // Otherwise the builder falls back to its built-in SingleTableJsonEventPersister default.
        var customPersister = eventPersisterProvider.getIfAvailable();
        if (customPersister != null) {
            builder.eventPersister(customPersister);
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static Set<Class<? extends Action<?, ?>>> scanActionClasses(
            BeanFactory beanFactory, Environment environment) {
        // AOT mode (including native image): EkbatanActionsAotProcessor ran the scan at
        // AOT processing time and emitted an initializer that populates this holder
        // before the auto-config runs. We just read the prepared list — the runtime
        // classpath scan below would silently return empty on native because there are
        // no .class files to walk in a native binary.
        var aotDiscovered = EkbatanActionsHolder.get();
        if (!aotDiscovered.isEmpty()) {
            var classes = new LinkedHashSet<Class<? extends Action<?, ?>>>(aotDiscovered.size());
            for (Class<?> cls : aotDiscovered) {
                if (!Action.class.isAssignableFrom(cls)) {
                    throw new IllegalStateException("EkbatanActionsHolder contained non-Action class " + cls.getName());
                }
                classes.add((Class<? extends Action<?, ?>>) cls);
            }
            return classes;
        }

        // Regular JVM mode: walk the classpath now.
        if (!AutoConfigurationPackages.has(beanFactory)) {
            return Set.of();
        }
        var packages = AutoConfigurationPackages.get(beanFactory);
        var scanner = new ClassPathScanningCandidateComponentProvider(false, environment);
        scanner.addIncludeFilter(new AnnotationTypeFilter(EkbatanAction.class));
        var classes = new LinkedHashSet<Class<? extends Action<?, ?>>>();
        for (var pkg : packages) {
            for (var bd : scanner.findCandidateComponents(pkg)) {
                Class<?> cls = ClassUtils.resolveClassName(bd.getBeanClassName(), null);
                if (!Action.class.isAssignableFrom(cls)) {
                    throw new IllegalStateException(
                            "@EkbatanAction on " + cls.getName() + " — class must extend " + Action.class.getName());
                }
                classes.add((Class<? extends Action<?, ?>>) cls);
            }
        }
        return classes;
    }
}
