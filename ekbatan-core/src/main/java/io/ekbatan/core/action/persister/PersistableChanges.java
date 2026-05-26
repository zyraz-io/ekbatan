package io.ekbatan.core.action.persister;

import io.ekbatan.core.domain.Persistable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The per-type staging area inside an {@link io.ekbatan.core.action.ActionPlan}: a map of IDs
 * to "to be added" entities and a separate map of IDs to "to be updated" entities, both
 * insertion-ordered.
 *
 * <p>Re-registering the same ID - whether for add or update - raises
 * {@link IllegalStateException}. This is deliberate: an action that stages a wallet for add
 * and then tries to update it has almost certainly drifted in its understanding of state and
 * the right answer is to surface the mistake at staging time rather than at commit time.
 *
 * @param <ID> the identifier type of the persistable entity.
 * @param <E> the concrete {@link Persistable} entity type being staged.
 */
public class PersistableChanges<ID extends Comparable<ID>, E extends Persistable<ID>> {

    /** Default no-arg constructor; framework instantiates per entity type lazily inside the plan. */
    public PersistableChanges() {}

    private final Map<ID, E> additions = new LinkedHashMap<>();
    private final Map<ID, E> updates = new LinkedHashMap<>();

    private void checkNotRegistered(ID id, String operation) {
        final var existingOperation =
                switch (id) {
                    case ID _ when additions.containsKey(id) -> "addition";
                    case ID _ when updates.containsKey(id) -> "update";
                    default -> null;
                };

        if (existingOperation != null) {
            throw new IllegalStateException("Entity with ID " + id + " is already registered for "
                    + existingOperation + " operation, cannot "
                    + operation);
        }
    }

    /**
     * Stages an entity for insert; raises if the ID is already registered for any operation.
     *
     * @param entity the entity to insert.
     */
    public void add(E entity) {
        ID id = entity.getId();
        checkNotRegistered(id, "add");
        additions.put(id, entity);
    }

    /**
     * Stages an entity for update; raises if the ID is already registered for any operation.
     *
     * @param entity the entity to update.
     */
    public void update(E entity) {
        ID id = entity.getId();
        checkNotRegistered(id, "update");
        updates.put(id, entity);
    }

    /** {@return an immutable view of the additions staged so far, in insertion order} */
    public Map<ID, E> additions() {
        return Collections.unmodifiableMap(additions);
    }

    /** {@return an immutable view of the updates staged so far, in insertion order} */
    public Map<ID, E> updates() {
        return Collections.unmodifiableMap(updates);
    }

    /** {@return {@code true} if any additions or updates have been staged} */
    public boolean hasChanges() {
        return !(additions.isEmpty() && updates.isEmpty());
    }
}
