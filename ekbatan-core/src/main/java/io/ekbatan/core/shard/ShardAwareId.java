package io.ekbatan.core.shard;

/**
 * An identifier that can self-resolve which shard its aggregate lives on. Implemented by
 * {@link io.ekbatan.core.domain.ShardedId}, where the shard identifier is encoded directly
 * into the UUID bits (see {@link io.ekbatan.core.shard.EmbeddedBitsShardingStrategy}).
 *
 * <p>The framework's sharding strategies use this hook to route reads and writes to the
 * correct shard without consulting an external lookup table. For aggregates whose shard is
 * NOT discoverable from the ID alone, implement a custom
 * {@link io.ekbatan.core.shard.ShardingStrategy} and return it from the repository's
 * {@code shardingStrategy()} method instead.
 */
public interface ShardAwareId {

    /** {@return the shard this aggregate lives on, decoded directly from the ID} */
    ShardIdentifier resolveShardIdentifier();
}
