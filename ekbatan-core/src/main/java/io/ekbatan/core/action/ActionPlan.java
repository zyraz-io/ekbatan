package io.ekbatan.core.action;

import io.ekbatan.core.domain.Persistable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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
