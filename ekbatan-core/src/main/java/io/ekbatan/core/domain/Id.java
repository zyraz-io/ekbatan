package io.ekbatan.core.domain;

import java.util.UUID;
import org.apache.commons.lang3.Validate;

public final class Id<IDENTIFIABLE extends Identifiable<?>> extends MicroType<UUID>
        implements ModelId<UUID>, Comparable<Id<IDENTIFIABLE>> {

    private Id(UUID value) {
        super(value);
    }

    public static <I extends Identifiable<?>> Id<I> of(Class<I> identifiableClass, String value) {
        return of(identifiableClass, UUID.fromString(value));
    }

    public static <I extends Identifiable<?>> Id<I> of(Class<I> identifiableClass, UUID value) {
        Validate.notNull(identifiableClass, "Identifiable class cannot be null");
        return new Id<>(value);
    }

    public static <I extends Identifiable<?>> Id<I> random(Class<I> identifiableClass) {
        Validate.notNull(identifiableClass, "Identifiable class cannot be null");
        return new Id<>(java.util.UUID.randomUUID());
    }

    @Override
    public UUID getId() {
        return getValue();
    }

    @Override
    public int compareTo(Id<IDENTIFIABLE> o) {
        return this.getValue().compareTo(o.getValue());
    }
}
