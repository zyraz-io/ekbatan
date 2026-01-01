package io.ekbatan.core.persistence.jooq.converter.mysql;

import java.nio.ByteBuffer;
import java.util.UUID;
import org.jooq.Converter;

public final class UuidBinaryConverter implements Converter<byte[], UUID> {

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
