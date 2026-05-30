package io.ekbatan.quarkus.runtime;

import io.ekbatan.core.config.ShardMemberConfig;
import io.ekbatan.core.config.ShardingConfig;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.distributedjobs.DistributedJob;
import io.ekbatan.distributedjobs.JobRegistry;
import io.ekbatan.distributedjobs.config.JobsConfig;
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

/**
 * Quarkus runtime CDI producer class for Ekbatan's distributed job scheduler. Discovered at
 * deployment time only when {@code JobRegistry} is present at runtime (see
 * {@code EkbatanProcessor.registerDistributedJobsProducers}); produces the
 * {@link JobRegistry} and its dedicated {@code ekbatanJobsConnectionProvider}.
 *
 * <p>Requires a {@code jobsConfig} {@link io.ekbatan.core.config.DataSourceConfig} under the
 * default shard's member - db-scheduler holds its own connection pool separate from the
 * main {@code DatabaseRegistry} to isolate job polling from application traffic.
 *
 * <p>Wired into the Quarkus lifecycle via {@link StartupEvent} / {@link ShutdownEvent}
 * observers - the scheduler thread starts when the app starts and shuts down cleanly when
 * the app shuts down.
 */
@Singleton
public class EkbatanDistributedJobsConfiguration {

    /** Required by CDI; the container instantiates this bean class to invoke its producer methods. */
    public EkbatanDistributedJobsConfiguration() {}

    /**
     * Produces the dedicated {@link ConnectionProvider} used by the job scheduler. Kept separate
     * from the main {@link io.ekbatan.core.shard.DatabaseRegistry} pools so job polling load can't
     * starve application traffic (or vice versa).
     *
     * @param shardingConfig the sharding configuration - the jobs datasource is read from the
     *     default shard's member under {@code configs.jobsConfig}.
     * @return a Hikari-backed provider; closed by {@link #closeJobsConnectionProvider}.
     */
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
                                        + "].configs.jobsConfig in your application.yaml - "
                                        + "or define a @Named(\"ekbatanJobsConnectionProvider\") ConnectionProvider @Produces of your own."));
        return ConnectionProvider.hikariConnectionProvider(jobsConfig);
    }

    /**
     * CDI disposer for {@link #ekbatanJobsConnectionProvider} - closes the dedicated Hikari pool
     * for the job scheduler at shutdown.
     *
     * @param provider the provider being disposed.
     */
    public void closeJobsConnectionProvider(
            @Disposes @Named("ekbatanJobsConnectionProvider") ConnectionProvider provider) {
        provider.close();
    }

    /**
     * Builds the {@link JobRegistry} from every {@code @EkbatanDistributedJob}-annotated bean,
     * applying the application's {@code ekbatan.jobs.*} tuning (poll interval, heartbeat,
     * shutdown grace period). Scheduler start/stop is wired separately via the
     * {@link StartupEvent} / {@link ShutdownEvent} observers below.
     *
     * @param jobsConnectionProvider the dedicated provider produced by {@link #ekbatanJobsConnectionProvider}.
     * @param jobsConfig the parsed {@code ekbatan.jobs.*} subtree.
     * @param jobs the application's distributed-job beans, injected via Arc {@code @All}.
     * @return the registry whose scheduler is started in {@link #onStartup}.
     */
    @Produces
    @Singleton
    public JobRegistry ekbatanJobRegistry(
            @Named("ekbatanJobsConnectionProvider") ConnectionProvider jobsConnectionProvider,
            JobsConfig jobsConfig,
            @All List<DistributedJob> jobs) {
        var builder = JobRegistry.jobRegistry()
                .connectionProvider(jobsConnectionProvider)
                .registerShutdownHook(false);
        jobsConfig.pollingInterval.ifPresent(builder::pollInterval);
        jobsConfig.heartbeatInterval.ifPresent(builder::heartbeatInterval);
        jobsConfig.shutdownMaxWait.ifPresent(builder::shutdownMaxWait);
        return builder.withJobs(jobs).build();
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
