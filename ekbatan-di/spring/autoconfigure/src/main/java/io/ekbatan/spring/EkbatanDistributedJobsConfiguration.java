package io.ekbatan.spring;

import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.core.shard.config.ShardMemberConfig;
import io.ekbatan.core.shard.config.ShardingConfig;
import io.ekbatan.distributedjobs.DistributedJob;
import io.ekbatan.distributedjobs.JobRegistry;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;

/**
 * Spring Boot auto-configuration for Ekbatan's distributed job scheduler (db-scheduler-backed).
 * Activates only when both conditions hold: {@code JobRegistry} is on the classpath (the
 * {@code ekbatan-distributed-jobs} dependency is present), and the application defines at
 * least one {@code @EkbatanDistributedJob} bean.
 *
 * <p>Requires a {@code jobsConfig} {@link io.ekbatan.core.config.DataSourceConfig} under the
 * default shard's member (e.g.
 * {@code ekbatan.sharding.groups[0].members[0].configs.jobsConfig.*}) - db-scheduler holds
 * its own connection pool separately from the main {@link io.ekbatan.core.shard.DatabaseRegistry}
 * to keep job polling from competing with application traffic for connections.
 */
@AutoConfiguration(after = {EkbatanCoreConfiguration.class, EkbatanLocalEventHandlerConfiguration.class})
@ConditionalOnClass(JobRegistry.class)
@ConditionalOnBean(DistributedJob.class)
public class EkbatanDistributedJobsConfiguration {

    /** Required by Spring; the container instantiates this auto-configuration class to invoke its {@code @Bean} methods. */
    public EkbatanDistributedJobsConfiguration() {}

    /**
     * Produces the dedicated {@link ConnectionProvider} used by the job scheduler. Kept separate
     * from the main {@link io.ekbatan.core.shard.DatabaseRegistry} pools so job polling load
     * can't starve application traffic (or vice versa).
     *
     * @param shardingConfig the sharding configuration - the jobs datasource is read from the
     *     default shard's member under {@code configs.jobsConfig}.
     * @return a Hikari-backed provider; closed automatically by Spring via {@code destroyMethod="close"}.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(name = "ekbatanJobsConnectionProvider")
    public ConnectionProvider ekbatanJobsConnectionProvider(ShardingConfig shardingConfig) {
        var defaultMember = findDefaultMember(shardingConfig);
        var jobsConfig = defaultMember
                .configFor("jobsConfig")
                .orElseThrow(() -> new IllegalStateException(
                        "Distributed jobs require a 'jobsConfig' DataSourceConfig under the default shard's member. "
                                + "Add it under sharding.groups["
                                + shardingConfig.defaultShard.group
                                + "].members["
                                + shardingConfig.defaultShard.member
                                + "].configs.jobsConfig in your application.yml - "
                                + "or define an ekbatanJobsConnectionProvider @Bean of your own."));
        return ConnectionProvider.hikariConnectionProvider(jobsConfig);
    }

    // @DependsOn forces ActionRegistry/ActionExecutor to be fully initialized before
    // JobRegistry.start() begins polling - otherwise a job could fire before the action graph
    // is wired. registerShutdownHook(false) hands lifecycle control to Spring (initMethod /
    // destroyMethod), which destroys in reverse-creation order so stop() drains in-flight jobs
    // before the data pools close.
    /**
     * Builds the {@link JobRegistry} from every {@code @EkbatanDistributedJob}-annotated bean,
     * applying the application's {@code ekbatan.jobs.*} tuning. {@code @DependsOn} ensures the
     * action graph is fully wired before {@code start()} begins polling; {@code initMethod} /
     * {@code destroyMethod} hand lifecycle control to Spring so {@code stop()} drains in-flight
     * jobs before pools close.
     *
     * @param jobsConnectionProvider the dedicated provider produced by {@link #ekbatanJobsConnectionProvider}.
     * @param properties the Ekbatan runtime configuration (reads {@code ekbatan.jobs.*}).
     * @param jobs the application's distributed-job beans, injected by Spring.
     * @return the registry whose scheduler is started by Spring's {@code initMethod} hook.
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnMissingBean
    @DependsOn({"ekbatanActionRegistry", "ekbatanActionExecutor"})
    public JobRegistry ekbatanJobRegistry(
            @Qualifier("ekbatanJobsConnectionProvider") ConnectionProvider jobsConnectionProvider,
            EkbatanProperties properties,
            List<DistributedJob> jobs) {
        var builder = JobRegistry.jobRegistry()
                .connectionProvider(jobsConnectionProvider)
                .registerShutdownHook(false);
        var j = properties.jobs();
        if (j.pollingInterval() != null) builder.pollInterval(j.pollingInterval());
        if (j.heartbeatInterval() != null) builder.heartbeatInterval(j.heartbeatInterval());
        if (j.shutdownMaxWait() != null) builder.shutdownMaxWait(j.shutdownMaxWait());
        return builder.withJobs(jobs).build();
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
