package io.ekbatan.spring;

import io.ekbatan.bootstrap.RegistryAssembler;
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

@AutoConfiguration(after = {EkbatanCoreConfiguration.class, EkbatanLocalEventHandlerConfiguration.class})
@ConditionalOnClass(JobRegistry.class)
@ConditionalOnBean(DistributedJob.class)
public class EkbatanDistributedJobsConfiguration {

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
                                + "].configs.jobsConfig in your application.yml — "
                                + "or define an ekbatanJobsConnectionProvider @Bean of your own."));
        return ConnectionProvider.hikariConnectionProvider(jobsConfig);
    }

    // @DependsOn forces ActionRegistry/ActionExecutor to be fully initialized before
    // JobRegistry.start() begins polling — otherwise a job could fire before the action graph
    // is wired. registerShutdownHook(false) hands lifecycle control to Spring (initMethod /
    // destroyMethod), which destroys in reverse-creation order so stop() drains in-flight jobs
    // before the data pools close.
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
        return RegistryAssembler.jobRegistry(builder, jobs);
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
