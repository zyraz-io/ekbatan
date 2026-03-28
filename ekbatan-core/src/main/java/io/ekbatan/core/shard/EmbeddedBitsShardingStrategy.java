package io.ekbatan.core.shard;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.Persistable;
import io.ekbatan.core.domain.ShardedId;
import java.util.Optional;
import java.util.UUID;

public final class EmbeddedBitsShardingStrategy implements ShardingStrategy<UUID> {

    public EmbeddedBitsShardingStrategy() {}

    @Override
    public boolean usesShardAwareId() {
        return true;
    }

    @Override
    public Optional<ShardIdentifier> resolveShardIdentifierById(UUID id) {
        if (id.version() != 7) {
            return Optional.empty();
        }
        return Optional.of(ShardedUUID.from(id).resolveShardIdentifier());
    }

    @Override
    public Optional<ShardIdentifier> resolveShardIdentifier(Persistable<?> persistable) {
        var id = persistable.getId();
        if (id instanceof ShardedId<?> sid) {
            return Optional.of(sid.resolveShardIdentifier());
        }
        if (id instanceof Id<?> regularId) {
            return resolveShardIdentifierById(regularId.getValue());
        }
        return Optional.empty();
    }
}
