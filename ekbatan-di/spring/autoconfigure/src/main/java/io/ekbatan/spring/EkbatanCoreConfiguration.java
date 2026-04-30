package io.ekbatan.spring;

import io.ekbatan.bootstrap.RegistryAssembler;
import io.ekbatan.bootstrap.jackson.EkbatanConfigJacksonModule;
import io.ekbatan.core.action.Action;
import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.action.ActionRegistry;
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

@AutoConfiguration
@EnableConfigurationProperties(EkbatanProperties.class)
@Import(EkbatanStereotypeBeanRegistrar.class)
public class EkbatanCoreConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EkbatanConfigJacksonModule ekbatanConfigJacksonModule() {
        return new EkbatanConfigJacksonModule();
    }

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

    @Bean
    @ConditionalOnMissingBean
    public DatabaseRegistry ekbatanDatabaseRegistry(ShardingConfig shardingConfig) {
        return DatabaseRegistry.fromConfig(shardingConfig);
    }

    @Bean
    @ConditionalOnMissingBean
    public Clock ekbatanClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public RepositoryRegistry ekbatanRepositoryRegistry(List<AbstractRepository<?, ?, ?, ?>> repositories) {
        return RegistryAssembler.repositoryRegistry(repositories);
    }

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
        return RegistryAssembler.actionRegistry(actions);
    }

    @Bean
    @ConditionalOnMissingBean
    public ActionExecutor ekbatanActionExecutor(
            EkbatanProperties properties,
            DatabaseRegistry databaseRegistry,
            ActionRegistry actionRegistry,
            RepositoryRegistry repositoryRegistry,
            ObjectMapper objectMapper,
            Clock clock) {
        return ActionExecutor.Builder.actionExecutor()
                .namespace(properties.namespace())
                .databaseRegistry(databaseRegistry)
                .actionRegistry(actionRegistry)
                .repositoryRegistry(repositoryRegistry)
                .objectMapper(objectMapper)
                .clock(clock)
                .build();
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
