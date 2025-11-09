package io.ekbatan.core.domain;

import java.util.UUID;
import org.apache.commons.lang3.Validate;

public final class Id<MODEL> extends MicroType<UUID> implements ModelId<UUID>, Comparable<Id<MODEL>> {

    private Id(UUID value) {
        super(value);
    }

    public static <M> Id<M> of(Class<M> modelClass, String value) {
        return of(modelClass, UUID.fromString(value));
    }

    public static <M> Id<M> of(Class<M> modelClass, UUID value) {
        Validate.notNull(modelClass, "Model class cannot be null");
        return new Id<>(value);
    }

    public static <M> Id<M> random(Class<M> modelClass) {
        Validate.notNull(modelClass, "Model class cannot be null");
        return new Id<>(java.util.UUID.randomUUID());
    }

    @Override
    public UUID getId() {
        return getValue();
    }

    @Override
    public int compareTo(Id<MODEL> o) {
        return this.getValue().compareTo(o.getValue());
    }
}
