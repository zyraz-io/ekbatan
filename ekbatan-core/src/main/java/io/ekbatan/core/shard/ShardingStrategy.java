package io.ekbatan.core.shard;

import io.ekbatan.core.domain.Persistable;
import java.util.Optional;

/**
 * Per-repository strategy that maps a {@link Persistable} (or its raw DB ID) to the shard it
 * lives on. The framework uses this hook to route reads and writes to the correct shard
 * without consulting an external lookup table.
 *
 * <p>Two built-in strategies cover the common cases:
 * <ul>
 *   <li>{@link NoShardingStrategy}: single-DB deployments. Every call returns
 *       {@link Optional#empty()}, which the executor interprets as "use the default shard."</li>
 *   <li>{@link EmbeddedBitsShardingStrategy}: the shard identifier is encoded directly into
 *       the UUID bytes of {@link io.ekbatan.core.domain.ShardedId}. No table lookup required.</li>
 * </ul>
 *
 * <p>For shard layouts where the shard isn't derivable from the ID - say, "shard by tenant
 * lookup table" - implement this interface yourself and have your repository return it from
 * {@link io.ekbatan.core.repository.Repository#shardingStrategy()}.
 *
 * @param <DB_ID> the storage-level identifier type (typically {@link java.util.UUID})
 */
public interface ShardingStrategy<DB_ID> {

    /** {@return {@code true} if this strategy can resolve the shard from the ID alone (no persistable required)} */
    boolean usesShardAwareId();

    /**
     * Resolves the shard for a given raw database identifier.
     *
     * @param id the raw {@code DB_ID}.
     * @return the resolved shard, or empty to fall back to the default shard.
     */
    Optional<ShardIdentifier> resolveShardIdentifierById(DB_ID id);

    /**
     * Resolves the shard for a given persistable.
     *
     * @param persistable the persistable instance.
     * @return the resolved shard, or empty to fall back to the default shard.
     */
    Optional<ShardIdentifier> resolveShardIdentifier(Persistable<?> persistable);
}
