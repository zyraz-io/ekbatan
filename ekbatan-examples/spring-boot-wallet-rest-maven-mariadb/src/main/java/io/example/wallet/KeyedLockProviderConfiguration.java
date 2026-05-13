package io.example.wallet;

import io.ekbatan.core.concurrent.KeyedLockProvider;
import io.ekbatan.core.concurrent.MariaDBKeyedLockProvider;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.shard.config.ShardingConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the dialect-specific {@link KeyedLockProvider} from the {@code lockConfig} datasource
 * declared in {@code ekbatan.sharding.groups[0].members[0].configs.lockConfig}. The framework
 * doesn't autoconfigure {@code KeyedLockProvider} — applications declare the bean explicitly.
 */
@Configuration
public class KeyedLockProviderConfiguration {

    @Bean
    public KeyedLockProvider keyedLockProvider(ShardingConfig shardingConfig) {
        final var defaultMember = shardingConfig.groups.getFirst().members.getFirst();
        final var lockConfig = defaultMember
                .configFor("lockConfig")
                .orElseThrow(() -> new IllegalStateException(
                        "ekbatan.sharding.groups[0].members[0].configs.lockConfig is required."));
        return MariaDBKeyedLockProvider.Builder.mariaDBKeyedLockProvider()
                .connectionProvider(ConnectionProvider.hikariConnectionProvider(lockConfig))
                .build();
    }
}
