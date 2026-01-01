package io.ekbatan.core.persistence.jooq.converter.mysql;

import java.util.UUID;
import org.jooq.Converter;

public final class UuidStringConverter implements Converter<String, UUID> {

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
