package io.ekbatan.micronaut;

import io.ekbatan.bootstrap.RegistryAssembler;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.core.shard.config.ShardMemberConfig;
import io.ekbatan.core.shard.config.ShardingConfig;
import io.ekbatan.distributedjobs.DistributedJob;
import io.ekbatan.distributedjobs.JobRegistry;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.ShutdownEvent;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.List;

@Factory
@Requires(classes = JobRegistry.class)
public class EkbatanDistributedJobsConfiguration {

    @Bean(preDestroy = "close")
    @Singleton
    @Named("ekbatanJobsConnectionProvider")
    public ConnectionProvider ekbatanJobsConnectionProvider(ShardingConfig shardingConfig) {
        var defaultMember = findDefaultMember(shardingConfig);
        var jobsConfig = defaultMember
                .configFor("jobsConfig")
                .orElseThrow(
                        () -> new IllegalStateException(
                                "Distributed jobs require a 'jobsConfig' DataSourceConfig under the default shard's member. "
                                        + "Add it under sharding.groups["
                                        + shardingConfig.defaultShard.group
                                        + "].members["
                                        + shardingConfig.defaultShard.member
                                        + "].configs.jobsConfig in your application.yml — "
                                        + "or define a @Named(\"ekbatanJobsConnectionProvider\") ConnectionProvider @Bean of your own."));
        return ConnectionProvider.hikariConnectionProvider(jobsConfig);
    }

    @Bean
    @Singleton
    public JobRegistry ekbatanJobRegistry(
            @Named("ekbatanJobsConnectionProvider") ConnectionProvider jobsConnectionProvider,
            EkbatanProperties properties,
            List<DistributedJob> jobs) {
        var builder = JobRegistry.jobRegistry()
                .connectionProvider(jobsConnectionProvider)
                .registerShutdownHook(false);
        var j = properties.getJobs();
        if (j.getPollingInterval() != null) builder.pollInterval(j.getPollingInterval());
        if (j.getHeartbeatInterval() != null) builder.heartbeatInterval(j.getHeartbeatInterval());
        if (j.getShutdownMaxWait() != null) builder.shutdownMaxWait(j.getShutdownMaxWait());
        return RegistryAssembler.jobRegistry(builder, jobs);
    }

    @Singleton
    @Requires(classes = JobRegistry.class)
    public static class Lifecycle implements ApplicationEventListener<StartupEvent> {

        private final JobRegistry registry;

        public Lifecycle(JobRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void onApplicationEvent(StartupEvent event) {
            registry.start();
        }

        @EventListener
        public void onShutdown(ShutdownEvent event) {
            registry.stop();
        }
    }

    private static ShardMemberConfig findDefaultMember(ShardingConfig cfg) {
        ShardIdentifier id = cfg.defaultShard;
        return cfg.groups.stream()
                .filter(g -> g.group == id.group)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "ShardingConfig has no group matching defaultShard.group=" + id.group))
                .members
                .stream()
                .filter(m -> m.member == id.member)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("ShardingConfig group " + id.group
                        + " has no member matching defaultShard.member=" + id.member));
    }
}
