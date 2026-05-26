package io.example.wallet.startup;

import io.ekbatan.core.concurrent.KeyedLockProvider;
import io.ekbatan.core.concurrent.PostgresKeyedLockProvider;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.shard.config.ShardingConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the dialect-specific {@link KeyedLockProvider} from the {@code lockConfig}
 * datasource declared in application.properties. The framework doesn't autoconfigure
 * KeyedLockProvider - applications declare the producer explicitly.
 */
@ApplicationScoped
public class KeyedLockProviderConfiguration {

    @Produces
    @Singleton
    public KeyedLockProvider keyedLockProvider(ShardingConfig shardingConfig) {
        final var defaultMember = shardingConfig.groups.getFirst().members.getFirst();
        final var lockConfig = defaultMember
                .configFor("lockConfig")
                .orElseThrow(() -> new IllegalStateException(
                        "ekbatan.sharding.groups[0].members[0].configs.lockConfig is required."));
        return PostgresKeyedLockProvider.Builder.postgresKeyedLockProvider()
                .connectionProvider(ConnectionProvider.hikariConnectionProvider(lockConfig))
                .build();
    }
}
