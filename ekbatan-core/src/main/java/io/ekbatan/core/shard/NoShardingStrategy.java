package io.ekbatan.core.shard;

import io.ekbatan.core.domain.Persistable;
import java.util.Optional;

/**
 * Sharding strategy for single-DB deployments: every call returns {@link Optional#empty()},
 * which {@link io.ekbatan.core.action.ActionExecutor} interprets as "route to the default
 * shard." This is the default strategy for repositories on non-sharded aggregates.
 *
 * @param <DB_ID> the storage-level identifier type
 */
public final class NoShardingStrategy<DB_ID> implements ShardingStrategy<DB_ID> {

    /** Constructs the strategy; instances are stateless and safely shared across repositories. */
    public NoShardingStrategy() {}

    @Override
    public boolean usesShardAwareId() {
        return false;
    }

    @Override
    public Optional<ShardIdentifier> resolveShardIdentifierById(DB_ID id) {
        return Optional.empty();
    }

    @Override
    public Optional<ShardIdentifier> resolveShardIdentifier(Persistable<?> persistable) {
        return Optional.empty();
    }
}
