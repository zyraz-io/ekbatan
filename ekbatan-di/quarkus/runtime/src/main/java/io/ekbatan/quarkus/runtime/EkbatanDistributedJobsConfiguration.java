package io.ekbatan.quarkus.runtime;

import io.ekbatan.bootstrap.RegistryAssembler;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.core.shard.config.ShardMemberConfig;
import io.ekbatan.core.shard.config.ShardingConfig;
import io.ekbatan.distributedjobs.DistributedJob;
import io.ekbatan.distributedjobs.JobRegistry;
import io.quarkus.arc.All;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.List;

@Singleton
public class EkbatanDistributedJobsConfiguration {

    @Produces
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
                                        + "].configs.jobsConfig in your application.yaml — "
                                        + "or define a @Named(\"ekbatanJobsConnectionProvider\") ConnectionProvider @Produces of your own."));
        return ConnectionProvider.hikariConnectionProvider(jobsConfig);
    }

    public void closeJobsConnectionProvider(
            @Disposes @Named("ekbatanJobsConnectionProvider") ConnectionProvider provider) {
        provider.close();
    }

    @Produces
    @Singleton
    public JobRegistry ekbatanJobRegistry(
            @Named("ekbatanJobsConnectionProvider") ConnectionProvider jobsConnectionProvider,
            EkbatanProperties config,
            @All List<DistributedJob> jobs) {
        var builder = JobRegistry.jobRegistry()
                .connectionProvider(jobsConnectionProvider)
                .registerShutdownHook(false);
        var j = config.jobs();
        j.pollingInterval().ifPresent(builder::pollInterval);
        j.heartbeatInterval().ifPresent(builder::heartbeatInterval);
        j.shutdownMaxWait().ifPresent(builder::shutdownMaxWait);
        return RegistryAssembler.jobRegistry(builder, jobs);
    }

    // Inject as Instance so this observer doesn't pull the registry into existence before the
    // rest of the app is wired (CDI evaluates injected types eagerly during the observer's
    // construction).
    void onStartup(@Observes StartupEvent event, Instance<JobRegistry> registry) {
        registry.get().start();
    }

    // ShutdownEvent rather than @PreDestroy: ShutdownEvent fires before Arc tears singletons
    // down, so the scheduler drains in-flight jobs while DatabaseRegistry / jobsConnectionProvider
    // are still open.
    void onShutdown(@Observes ShutdownEvent event, Instance<JobRegistry> registry) {
        if (registry.isResolvable()) {
            registry.get().stop();
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
