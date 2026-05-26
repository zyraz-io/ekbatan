package io.ekbatan.core.domain;

/**
 * Default state enum for {@link Model} / {@link Entity} subclasses that don't need a richer
 * lifecycle of their own. {@code DELETED} is the canonical soft-delete sentinel - the
 * {@code AbstractRepository.notDeleted(...)} predicate filters on it.
 *
 * <p>Aggregates with a domain-specific lifecycle (e.g. {@code OPENED, CLOSED, FROZEN} for an
 * account) should declare their own enum instead and use this only as a reference for what
 * the "minimal viable state enum" looks like.
 */
public enum GenericState {
    /** The aggregate is live and visible to queries. */
    ACTIVE,

    /** The aggregate is soft-deleted; filtered out by the framework's read paths. */
    DELETED
}
