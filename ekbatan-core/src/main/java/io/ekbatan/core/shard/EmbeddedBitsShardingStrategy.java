package io.ekbatan.core.shard;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.Persistable;
import io.ekbatan.core.domain.ShardedId;
import java.util.Optional;
import java.util.UUID;

/**
 * Sharding strategy that reads the shard identifier directly out of the UUID bytes — no
 * external lookup. Used together with {@link ShardedId} (and the underlying
 * {@code ShardedUUID}), which encode {@code (group, member)} into specific bits of a
 * UUIDv7 value.
 *
 * <p>Resolving the shard is two-tier:
 * <ol>
 *   <li>If the persistable's ID is a {@link ShardedId}, decode the shard directly.</li>
 *   <li>Otherwise, if the ID is an {@link Id} wrapping a UUIDv7, attempt to decode the bits
 *       anyway (lets a legacy non-sharded aggregate live on a sharded layout, provided its
 *       UUIDs were minted with shard-aware version 7).</li>
 *   <li>Anything else returns {@link Optional#empty()} and the executor falls back to the
 *       default shard.</li>
 * </ol>
 */
public final class EmbeddedBitsShardingStrategy implements ShardingStrategy<UUID> {

    /** Constructs the strategy; instances are stateless and safely shared across repositories. */
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
