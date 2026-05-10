package io.ekbatan.micronaut;

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

/**
 * Micronaut {@code @Factory} for Ekbatan's distributed job scheduler. Activates only when
 * {@code JobRegistry} is on the classpath (the {@code ekbatan-distributed-jobs} dependency
 * is in the build).
 *
 * <p>Requires a {@code jobsConfig} {@link io.ekbatan.core.config.DataSourceConfig} under the
 * default shard's member — db-scheduler holds its own connection pool separate from the
 * main {@code DatabaseRegistry} to isolate job polling from application traffic.
 *
 * <p>Wired into the Micronaut lifecycle via {@link StartupEvent} / {@link ShutdownEvent}
 * listeners — the scheduler thread starts when the app starts and shuts down cleanly on app
 * stop.
 */
@Factory
@Requires(classes = JobRegistry.class)
public class EkbatanDistributedJobsConfiguration {

    /** Required by Micronaut; the container instantiates this {@code @Factory} class to invoke its {@code @Bean} methods. */
    public EkbatanDistributedJobsConfiguration() {}

    /**
     * Produces the dedicated {@link ConnectionProvider} used by the job scheduler. Kept separate
     * from the main {@link io.ekbatan.core.shard.DatabaseRegistry} pools so job polling load
     * can't starve application traffic (or vice versa).
     *
     * @param shardingConfig the sharding configuration — the jobs datasource is read from the
     *     default shard's member under {@code configs.jobsConfig}.
     * @return a Hikari-backed provider; closed automatically by Micronaut via {@code preDestroy="close"}.
     */
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

    /**
     * Builds the {@link JobRegistry} from every {@code @EkbatanDistributedJob}-annotated bean,
     * applying the application's {@code ekbatan.jobs.*} tuning. Scheduler start/stop is wired
     * separately via the {@link Lifecycle} listener below.
     *
     * @param jobsConnectionProvider the dedicated provider produced by {@link #ekbatanJobsConnectionProvider}.
     * @param properties the Ekbatan runtime configuration (reads {@code ekbatan.jobs.*}).
     * @param jobs the application's distributed-job beans, injected by Micronaut.
     * @return the registry whose scheduler is started in {@link Lifecycle#onApplicationEvent}.
     */
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
        return builder.withJobs(jobs).build();
    }

    /**
     * Starts and stops the {@link JobRegistry}'s scheduler thread on the Micronaut application
     * lifecycle. {@link StartupEvent} starts the scheduler so it begins polling for due jobs;
     * {@link ShutdownEvent} stops it, draining in-flight executions per the configured
     * {@code shutdownMaxWait}.
     */
    @Singleton
    @Requires(classes = JobRegistry.class)
    public static class Lifecycle implements ApplicationEventListener<StartupEvent> {

        private final JobRegistry registry;

        /**
         * Constructed by Micronaut with the singleton {@link JobRegistry} produced by the
         * surrounding {@code @Factory}.
         *
         * @param registry the registry whose scheduler thread this lifecycle controls.
         */
        public Lifecycle(JobRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void onApplicationEvent(StartupEvent event) {
            registry.start();
        }

        /**
         * Stops the {@link JobRegistry} on Micronaut's shutdown event so in-flight jobs drain
         * before connection pools close.
         *
         * @param event the Micronaut shutdown event (only the fact of shutdown is consumed).
         */
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
