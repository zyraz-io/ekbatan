package io.ekbatan.core.action;

import io.ekbatan.core.action.persister.PersistableChanges;
import io.ekbatan.core.domain.Persistable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The accumulator for one action execution's intended persistence changes.
 *
 * <p>An {@code ActionPlan} is created fresh per {@code ActionExecutor.execute(...)} call (or
 * per {@code ActionSpec.execute(...)} in tests) and bound as a {@link java.lang.ScopedValue} for
 * the duration of {@link Action#perform(java.security.Principal, Object)}. Inside the action,
 * code reaches it via {@link Action#plan()}.
 *
 * <h2>Single-writer; not thread-safe</h2>
 *
 * <p>{@code ActionPlan} uses a plain {@link LinkedHashMap} internally and is intentionally
 * <b>not</b> thread-safe. Within an action's {@code perform()}, mutations via {@link #add},
 * {@link #update}, {@link #addAll}, and {@link #updateAll} must happen on the executing thread.
 *
 * <p>If the action spawns parallel work to gather data, join the children first and only then
 * mutate the plan from the thread that invoked {@code perform()}. Calling {@code plan.add(...)}
 * from inside a spawned task is undefined behavior — concurrent mutations of the underlying
 * map are a data race.
 */
public class ActionPlan {
    private final Map<Class<? extends Persistable<?>>, PersistableChanges<?, ?>> changesByType = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public <ID extends Comparable<ID>, E extends Persistable<ID>> E add(E entity) {
        getOrCreateChanges((Class<E>) entity.getClass()).add(entity);
        return entity;
    }

    public <ID extends Comparable<ID>> Collection<? extends Persistable<ID>> addAll(
            Collection<? extends Persistable<ID>> entities) {
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }
        entities.forEach(this::add);
        return entities;
    }

    @SuppressWarnings("unchecked")
    public <ID extends Comparable<ID>, E extends Persistable<ID>> E update(E entity) {
        getOrCreateChanges((Class<E>) entity.getClass()).update(entity);
        return entity.nextVersion();
    }

    public <ID extends Comparable<ID>> Collection<? extends Persistable<ID>> updateAll(
            Collection<? extends Persistable<ID>> entities) {
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }

        return entities.stream().map(this::update).toList();
    }

    @SuppressWarnings("unchecked")
    private <ID extends Comparable<ID>, E extends Persistable<ID>> PersistableChanges<ID, E> getOrCreateChanges(
            Class<E> entityClass) {
        return (PersistableChanges<ID, E>) changesByType.computeIfAbsent(entityClass, _ -> new PersistableChanges<>());
    }

    @SuppressWarnings("unchecked")
    public <ID extends Comparable<ID>, E extends Persistable<ID>> Map<ID, E> additions(Class<E> entityClass) {
        PersistableChanges<ID, E> changes = (PersistableChanges<ID, E>) changesByType.get(entityClass);
        return changes != null ? changes.additions() : Map.of();
    }

    @SuppressWarnings("unchecked")
    public <ID extends Comparable<ID>, E extends Persistable<ID>> Map<ID, E> updates(Class<E> entityClass) {
        PersistableChanges<ID, E> changes = (PersistableChanges<ID, E>) changesByType.get(entityClass);
        return changes != null ? changes.updates() : Map.of();
    }

    public Map<Class<? extends Persistable<?>>, PersistableChanges<?, ?>> changes() {
        return Collections.unmodifiableMap(changesByType);
    }

    public boolean hasChanges() {
        return !changesByType.isEmpty() && changesByType.values().stream().anyMatch(PersistableChanges::hasChanges);
    }
}
