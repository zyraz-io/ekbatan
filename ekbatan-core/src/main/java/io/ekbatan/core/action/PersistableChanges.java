package io.ekbatan.core.action;

import io.ekbatan.core.domain.Persistable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class PersistableChanges<ID extends Comparable<ID>, E extends Persistable<ID>> {
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

    public void add(E entity) {
        ID id = entity.getId();
        checkNotRegistered(id, "add");
        additions.put(id, entity);
    }

    public void update(E entity) {
        ID id = entity.getId();
        checkNotRegistered(id, "update");
        updates.put(id, entity);
    }

    public Map<ID, E> additions() {
        return Collections.unmodifiableMap(additions);
    }

    public Map<ID, E> updates() {
        return Collections.unmodifiableMap(updates);
    }

    public boolean hasChanges() {
        return !(additions.isEmpty() && updates.isEmpty());
    }
}
