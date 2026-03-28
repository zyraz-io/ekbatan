package io.ekbatan.core.shard;

import io.ekbatan.core.domain.Persistable;
import java.util.Optional;

public final class NoShardingStrategy<DB_ID> implements ShardingStrategy<DB_ID> {

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
