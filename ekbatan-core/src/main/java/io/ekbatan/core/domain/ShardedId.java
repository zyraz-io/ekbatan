package io.ekbatan.core.domain;

import io.ekbatan.core.shard.ShardAwareId;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.core.shard.ShardedUUID;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.Validate;

/**
 * A type-parameterised UUID identifier that <em>also</em> encodes the shard the aggregate
 * lives on. Use this for sharded aggregates; non-sharded aggregates take {@link Id} instead.
 *
 * <p>The shard identifier is embedded in the UUID bytes themselves (see {@code ShardedUUID})
 * so reads and writes can route to the correct shard without consulting a lookup table:
 * {@link #resolveShardIdentifier()} extracts it directly from the ID.
 *
 * <p>Construct via {@link #generate(Class, ShardIdentifier)} for new aggregates (allocates a
 * fresh ID on the given shard) or {@link #of(Class, ShardedUUID)} when round-tripping from
 * storage.
 *
 * @param <IDENTIFIABLE> the {@link Identifiable} this ID refers to
 */
public final class ShardedId<IDENTIFIABLE extends Identifiable<?>>
        implements ShardAwareId, ModelId<UUID>, Comparable<ShardedId<IDENTIFIABLE>> {

    private final ShardedUUID shardedUUID;

    private ShardedId(ShardedUUID shardedUUID) {
        this.shardedUUID = shardedUUID;
    }

    /**
     * Wraps an existing {@link ShardedUUID} into a typed {@code ShardedId}.
     *
     * @param identifiableClass static-type witness for the target identifiable.
     * @param shardedUUID the sharded UUID.
     * @param <I> the identifiable type.
     * @return a typed {@code ShardedId}.
     */
    public static <I extends Identifiable<?>> ShardedId<I> of(Class<I> identifiableClass, ShardedUUID shardedUUID) {
        Validate.notNull(identifiableClass, "Identifiable class cannot be null");
        Validate.notNull(shardedUUID, "shardedUUID cannot be null");
        return new ShardedId<>(shardedUUID);
    }

    /**
     * Generates a fresh {@link ShardedUUID} for the given shard and wraps it as a typed
     * {@code ShardedId}.
     *
     * @param identifiableClass static-type witness for the target identifiable.
     * @param shard the shard the new aggregate will live on.
     * @param <I> the identifiable type.
     * @return a typed {@code ShardedId} wrapping a fresh UUID encoding {@code shard}.
     */
    public static <I extends Identifiable<?>> ShardedId<I> generate(Class<I> identifiableClass, ShardIdentifier shard) {
        Validate.notNull(identifiableClass, "Identifiable class cannot be null");
        Validate.notNull(shard, "shard cannot be null");
        return new ShardedId<>(ShardedUUID.generate(shard));
    }

    @Override
    public ShardIdentifier resolveShardIdentifier() {
        return shardedUUID.resolveShardIdentifier();
    }

    @Override
    public UUID getId() {
        return shardedUUID.value();
    }

    /** {@return the wrapped {@link UUID} value} */
    public UUID getValue() {
        return shardedUUID.value();
    }

    @Override
    public int compareTo(ShardedId<IDENTIFIABLE> o) {
        return this.shardedUUID.value().compareTo(o.shardedUUID.value());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShardedId<?> that)) return false;
        return Objects.equals(shardedUUID, that.shardedUUID);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(shardedUUID);
    }

    @Override
    public String toString() {
        return shardedUUID.value().toString();
    }
}
