package io.ekbatan.core.domain;

import io.ekbatan.core.internal.Validate;
import java.util.UUID;

/**
 * A type-parameterised UUID identifier. {@code Id<Wallet>} and {@code Id<Account>} are
 * statically incompatible - accidentally passing a wallet ID where an account ID is expected
 * fails at compile time rather than at runtime. Use this for non-sharded aggregates;
 * sharding-aware aggregates take {@link ShardedId} instead.
 *
 * <p>Construct via {@link #random(Class)} for new aggregates, or {@link #of(Class, String)} /
 * {@link #of(Class, UUID)} when round-tripping from storage. The {@code identifiableClass}
 * argument is a static-type witness only - it isn't stored on the {@code Id} instance, so
 * equality and hashing are based purely on the UUID value.
 *
 * @param <IDENTIFIABLE> the {@link Identifiable} this ID refers to
 */
public final class Id<IDENTIFIABLE extends Identifiable<?>> extends TypedValue<UUID>
        implements ModelId<UUID>, Comparable<Id<IDENTIFIABLE>> {

    private Id(UUID value) {
        super(value);
    }

    /**
     * Parses a string UUID into a typed {@code Id}.
     *
     * @param identifiableClass static-type witness for the target identifiable.
     * @param value the UUID as a string.
     * @param <I> the identifiable type.
     * @return a typed {@code Id} wrapping the parsed UUID.
     */
    public static <I extends Identifiable<?>> Id<I> of(Class<I> identifiableClass, String value) {
        return of(identifiableClass, UUID.fromString(value));
    }

    /**
     * Wraps an existing {@link UUID} into a typed {@code Id}.
     *
     * @param identifiableClass static-type witness for the target identifiable.
     * @param value the UUID.
     * @param <I> the identifiable type.
     * @return a typed {@code Id} wrapping {@code value}.
     */
    public static <I extends Identifiable<?>> Id<I> of(Class<I> identifiableClass, UUID value) {
        Validate.notNull(identifiableClass, "Identifiable class cannot be null");
        return new Id<>(value);
    }

    /**
     * Generates a random {@link UUID} and wraps it as a typed {@code Id}.
     *
     * @param identifiableClass static-type witness for the target identifiable.
     * @param <I> the identifiable type.
     * @return a typed {@code Id} wrapping a fresh random UUID.
     */
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

    @Override
    public String toString() {
        return getValue().toString();
    }
}
