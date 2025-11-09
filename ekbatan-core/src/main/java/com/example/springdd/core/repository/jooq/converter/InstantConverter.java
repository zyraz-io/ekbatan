package com.example.springdd.core.repository.jooq.converter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.jooq.Converter;

public class InstantConverter implements Converter<LocalDateTime, Instant> {
    @Override
    public Instant from(LocalDateTime databaseObject) {
        if (databaseObject == null) {
            return null;
        }
        // Assuming the stored TIMESTAMP (without time zone) is meant to be in UTC
        return databaseObject.toInstant(ZoneOffset.UTC);
    }

    @Override
    public LocalDateTime to(Instant userObject) {
        if (userObject == null) {
            return null;
        }
        // Convert the Instant back to LocalDateTime using UTC for storage
        return LocalDateTime.ofInstant(userObject, ZoneOffset.UTC);
    }

    @Override
    public Class<LocalDateTime> fromType() {
        return LocalDateTime.class;
    }

    @Override
    public Class<Instant> toType() {
        return Instant.class;
    }
}
