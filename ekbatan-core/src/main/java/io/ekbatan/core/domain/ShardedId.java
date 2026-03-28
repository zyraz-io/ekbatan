package io.ekbatan.core.domain;

import io.ekbatan.core.shard.ShardAwareId;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.core.shard.ShardedUUID;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.Validate;

public final class ShardedId<IDENTIFIABLE extends Identifiable<?>>
        implements ShardAwareId, ModelId<UUID>, Comparable<ShardedId<IDENTIFIABLE>> {

    private final ShardedUUID shardedUUID;

    private ShardedId(ShardedUUID shardedUUID) {
        this.shardedUUID = shardedUUID;
    }

    public static <I extends Identifiable<?>> ShardedId<I> of(Class<I> identifiableClass, ShardedUUID shardedUUID) {
        Validate.notNull(identifiableClass, "Identifiable class cannot be null");
        Validate.notNull(shardedUUID, "shardedUUID cannot be null");
        return new ShardedId<>(shardedUUID);
    }

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
