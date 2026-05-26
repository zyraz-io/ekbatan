package io.ekbatan.core.persistence.jooq.converter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.jooq.Converter;

/**
 * jOOQ converter mapping SQL {@code TIMESTAMP} (without time zone) to {@link Instant},
 * treating stored values as UTC. The framework's domain types use {@link Instant} everywhere
 * for timestamp fields ({@code createdDate}, {@code updatedDate}, etc.); this converter is
 * the bridge for any column generated as {@code TIMESTAMP} rather than {@code TIMESTAMPTZ}.
 *
 * <p>Wire-up: register via jOOQ codegen's {@code forcedTypes} block - see the integration-test
 * modules' {@code build.gradle.kts} for an example.
 */
public class InstantConverter implements Converter<LocalDateTime, Instant> {

    /** Constructs the converter; jOOQ instantiates it reflectively when wired through {@code forcedTypes}. */
    public InstantConverter() {}

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
