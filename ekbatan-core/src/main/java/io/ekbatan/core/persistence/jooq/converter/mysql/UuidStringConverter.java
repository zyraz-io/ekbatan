package io.ekbatan.core.persistence.jooq.converter.mysql;

import java.util.UUID;
import org.jooq.Converter;

/**
 * jOOQ converter mapping a MySQL {@code CHAR(36)} column to {@link UUID}. MySQL has no
 * native UUID type; this is the canonical "store UUIDs as their hyphenated string form"
 * choice - readable in {@code SELECT}, indexable as a normal string column, but ~2.25x the
 * storage of {@link UuidBinaryConverter}'s {@code BINARY(16)} variant.
 *
 * <p>The framework's MySQL repositories use this for non-sharded aggregates' IDs; the binary
 * converter is preferred for sharded aggregates where the UUIDv7 bit layout matters.
 */
public final class UuidStringConverter implements Converter<String, UUID> {

    /** Constructs the converter; jOOQ instantiates it reflectively when wired through {@code forcedTypes}. */
    public UuidStringConverter() {}

    @Override
    public UUID from(String databaseObject) {
        if (databaseObject == null) {
            return null;
        }
        return UUID.fromString(databaseObject);
    }

    @Override
    public String to(UUID userObject) {
        if (userObject == null) {
            return null;
        }
        return userObject.toString();
    }

    @Override
    public Class<String> fromType() {
        return String.class;
    }

    @Override
    public Class<UUID> toType() {
        return UUID.class;
    }
}
