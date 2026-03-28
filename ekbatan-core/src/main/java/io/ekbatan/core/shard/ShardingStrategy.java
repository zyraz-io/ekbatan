package io.ekbatan.core.shard;

import io.ekbatan.core.domain.Persistable;
import java.util.Optional;

public interface ShardingStrategy<DB_ID> {

    boolean usesShardAwareId();

    Optional<ShardIdentifier> resolveShardIdentifierById(DB_ID id);

    Optional<ShardIdentifier> resolveShardIdentifier(Persistable<?> persistable);
}
