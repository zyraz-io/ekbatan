package io.example.wallet;

import io.ekbatan.core.concurrent.KeyedLockProvider;
import io.ekbatan.core.concurrent.MySQLKeyedLockProvider;
import io.ekbatan.core.config.ShardingConfig;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

/**
 * Produces the dialect-specific {@link KeyedLockProvider} from the {@code lockConfig}
 * datasource declared in application.yml. The framework doesn't autoconfigure
 * KeyedLockProvider - applications declare the producer explicitly.
 */
@Factory
public class KeyedLockProviderFactory {

    @Singleton
    public KeyedLockProvider keyedLockProvider(ShardingConfig shardingConfig) {
        final var defaultMember = shardingConfig.groups.getFirst().members.getFirst();
        final var lockConfig = defaultMember
                .configFor("lockConfig")
                .orElseThrow(() -> new IllegalStateException(
                        "ekbatan.sharding.groups[0].members[0].configs.lockConfig is required."));
        return MySQLKeyedLockProvider.Builder.mySQLKeyedLockProvider()
                .connectionProvider(ConnectionProvider.hikariConnectionProvider(lockConfig))
                .build();
    }
}
