package io.ekbatan.core.persistence.jooq.converter.mysql;

import java.nio.ByteBuffer;
import java.util.UUID;
import org.jooq.Converter;

/**
 * jOOQ converter mapping a MySQL {@code BINARY(16)} column to {@link UUID}. The more compact
 * (and more index-friendly) of the two MySQL UUID-storage options — half the bytes of
 * {@link UuidStringConverter}'s {@code CHAR(36)} form, and B-tree indexes stay cache-friendly
 * even for high-cardinality tables.
 *
 * <p>Preferred for sharded aggregates where the UUIDv7 bit layout encodes the shard identifier
 * (see {@link io.ekbatan.core.shard.EmbeddedBitsShardingStrategy}) — losslessly round-tripped
 * through {@code BINARY(16)} but not through {@code CHAR(36)}-via-string.
 */
public final class UuidBinaryConverter implements Converter<byte[], UUID> {

    /** Constructs the converter; jOOQ instantiates it reflectively when wired through {@code forcedTypes}. */
    public UuidBinaryConverter() {}

    @Override
    public UUID from(byte[] databaseObject) {
        if (databaseObject == null) {
            return null;
        }

        final var bb = ByteBuffer.wrap(databaseObject);
        long most = bb.getLong();
        long least = bb.getLong();
        return new UUID(most, least);
    }

    @Override
    public byte[] to(UUID userObject) {
        if (userObject == null) {
            return null;
        }

        final var bb = ByteBuffer.allocate(16);
        bb.putLong(userObject.getMostSignificantBits());
        bb.putLong(userObject.getLeastSignificantBits());
        return bb.array();
    }

    @Override
    public Class<byte[]> fromType() {
        return byte[].class;
    }

    @Override
    public Class<UUID> toType() {
        return UUID.class;
    }
}
