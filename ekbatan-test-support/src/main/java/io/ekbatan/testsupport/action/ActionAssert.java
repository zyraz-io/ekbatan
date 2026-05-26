package io.ekbatan.testsupport.action;

import io.ekbatan.core.action.ActionPlan;
import io.ekbatan.core.domain.Model;
import io.ekbatan.core.domain.ModelEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Fluent assertions over the result and {@link ActionPlan} produced by {@link ActionSpec}.
 *
 * @param <R> action result type.
 */
public class ActionAssert<R> {

    private final R result;
    private final ActionPlan plan;

    ActionAssert(R result, ActionPlan plan) {
        this.result = result;
        this.plan = plan;
    }

    // --- Additions ---

    /**
     * Asserts that exactly one entity of {@code type} was staged for addition and verifies it.
     *
     * @param <E> staged entity type.
     * @param type entity class to inspect.
     * @param verifier verifier invoked with the staged entity.
     * @return this assertion object.
     */
    public <E> ActionAssert<R> assertAdded(Class<E> type, Consumer<E> verifier) {
        var added = getAdditions(type);
        if (added.size() != 1) {
            throw new AssertionError(
                    "Expected exactly 1 addition of " + type.getSimpleName() + " but found " + added.size());
        }
        verifier.accept(added.getFirst());
        return this;
    }

    /**
     * Asserts that {@code count} entities of {@code type} were staged for addition.
     *
     * @param <E> staged entity type.
     * @param type entity class to inspect.
     * @param count expected number of additions.
     * @return this assertion object.
     */
    public <E> ActionAssert<R> assertAdded(Class<E> type, int count) {
        var added = getAdditions(type);
        if (added.size() != count) {
            throw new AssertionError(
                    "Expected " + count + " addition(s) of " + type.getSimpleName() + " but found " + added.size());
        }
        return this;
    }

    /**
     * Asserts addition count for {@code type} and verifies the staged entities.
     *
     * @param <E> staged entity type.
     * @param type entity class to inspect.
     * @param count expected number of additions.
     * @param verifier verifier invoked with the staged entities.
     * @return this assertion object.
     */
    public <E> ActionAssert<R> assertAdded(Class<E> type, int count, Consumer<List<E>> verifier) {
        var added = getAdditions(type);
        if (added.size() != count) {
            throw new AssertionError(
                    "Expected " + count + " addition(s) of " + type.getSimpleName() + " but found " + added.size());
        }
        verifier.accept(added);
        return this;
    }

    /**
     * Asserts that the action staged no additions.
     *
     * @return this assertion object.
     */
    public ActionAssert<R> assertNoAdditions() {
        var total = plan.changes().values().stream()
                .mapToInt(c -> c.additions().size())
                .sum();
        if (total > 0) {
            throw new AssertionError("Expected no additions but found " + total);
        }
        return this;
    }

    /**
     * Asserts that the action staged no additions of {@code type}.
     *
     * @param <E> staged entity type.
     * @param type entity class to inspect.
     * @return this assertion object.
     */
    public <E> ActionAssert<R> assertNoAdditionsOf(Class<E> type) {
        var added = getAdditions(type);
        if (!added.isEmpty()) {
            throw new AssertionError("Expected no additions of " + type.getSimpleName() + " but found " + added.size());
        }
        return this;
    }

    /**
     * Asserts the total number of staged additions across all entity types.
     *
     * @param count expected total number of additions.
     * @return this assertion object.
     */
    public ActionAssert<R> assertTotalAdditions(int count) {
        var total = plan.changes().values().stream()
                .mapToInt(c -> c.additions().size())
                .sum();
        if (total != count) {
            throw new AssertionError("Expected " + count + " total addition(s) but found " + total);
        }
        return this;
    }

    // --- Updates ---

    /**
     * Asserts that exactly one entity of {@code type} was staged for update and verifies it.
     *
     * @param <E> staged entity type.
     * @param type entity class to inspect.
     * @param verifier verifier invoked with the staged entity.
     * @return this assertion object.
     */
    public <E> ActionAssert<R> assertUpdated(Class<E> type, Consumer<E> verifier) {
        var updated = getUpdates(type);
        if (updated.size() != 1) {
            throw new AssertionError(
                    "Expected exactly 1 update of " + type.getSimpleName() + " but found " + updated.size());
        }
        verifier.accept(updated.getFirst());
        return this;
    }

    /**
     * Asserts that {@code count} entities of {@code type} were staged for update.
     *
     * @param <E> staged entity type.
     * @param type entity class to inspect.
     * @param count expected number of updates.
     * @return this assertion object.
     */
    public <E> ActionAssert<R> assertUpdated(Class<E> type, int count) {
        var updated = getUpdates(type);
        if (updated.size() != count) {
            throw new AssertionError(
                    "Expected " + count + " update(s) of " + type.getSimpleName() + " but found " + updated.size());
        }
        return this;
    }

    /**
     * Asserts update count for {@code type} and verifies the staged entities.
     *
     * @param <E> staged entity type.
     * @param type entity class to inspect.
     * @param count expected number of updates.
     * @param verifier verifier invoked with the staged entities.
     * @return this assertion object.
     */
    public <E> ActionAssert<R> assertUpdated(Class<E> type, int count, Consumer<List<E>> verifier) {
        var updated = getUpdates(type);
        if (updated.size() != count) {
            throw new AssertionError(
                    "Expected " + count + " update(s) of " + type.getSimpleName() + " but found " + updated.size());
        }
        verifier.accept(updated);
        return this;
    }

    /**
     * Asserts that the action staged no updates.
     *
     * @return this assertion object.
     */
    public ActionAssert<R> assertNoUpdates() {
        var total = plan.changes().values().stream()
                .mapToInt(c -> c.updates().size())
                .sum();
        if (total > 0) {
            throw new AssertionError("Expected no updates but found " + total);
        }
        return this;
    }

    /**
     * Asserts that the action staged no updates of {@code type}.
     *
     * @param <E> staged entity type.
     * @param type entity class to inspect.
     * @return this assertion object.
     */
    public <E> ActionAssert<R> assertNoUpdatesOf(Class<E> type) {
        var updated = getUpdates(type);
        if (!updated.isEmpty()) {
            throw new AssertionError("Expected no updates of " + type.getSimpleName() + " but found " + updated.size());
        }
        return this;
    }

    /**
     * Asserts the total number of staged updates across all entity types.
     *
     * @param count expected total number of updates.
     * @return this assertion object.
     */
    public ActionAssert<R> assertTotalUpdates(int count) {
        var total = plan.changes().values().stream()
                .mapToInt(c -> c.updates().size())
                .sum();
        if (total != count) {
            throw new AssertionError("Expected " + count + " total update(s) but found " + total);
        }
        return this;
    }

    // --- Events ---

    /**
     * Asserts that at least one event of {@code type} was emitted.
     *
     * @param <E> event type.
     * @param type event class to inspect.
     * @return this assertion object.
     */
    public <E extends ModelEvent<?>> ActionAssert<R> assertEmitted(Class<E> type) {
        var events = getEvents(type);
        if (events.isEmpty()) {
            throw new AssertionError("Expected at least 1 event of " + type.getSimpleName() + " but none were emitted");
        }
        return this;
    }

    /**
     * Asserts that exactly one event of {@code type} was emitted and verifies it.
     *
     * @param <E> event type.
     * @param type event class to inspect.
     * @param verifier verifier invoked with the emitted event.
     * @return this assertion object.
     */
    public <E extends ModelEvent<?>> ActionAssert<R> assertEmitted(Class<E> type, Consumer<E> verifier) {
        var events = getEvents(type);
        if (events.size() != 1) {
            throw new AssertionError(
                    "Expected exactly 1 event of " + type.getSimpleName() + " but found " + events.size());
        }
        verifier.accept(events.getFirst());
        return this;
    }

    /**
     * Asserts that {@code count} events of {@code type} were emitted.
     *
     * @param <E> event type.
     * @param type event class to inspect.
     * @param count expected number of emitted events.
     * @return this assertion object.
     */
    public <E extends ModelEvent<?>> ActionAssert<R> assertEmitted(Class<E> type, int count) {
        var events = getEvents(type);
        if (events.size() != count) {
            throw new AssertionError(
                    "Expected " + count + " event(s) of " + type.getSimpleName() + " but found " + events.size());
        }
        return this;
    }

    /**
     * Asserts event count for {@code type} and verifies the emitted events.
     *
     * @param <E> event type.
     * @param type event class to inspect.
     * @param count expected number of emitted events.
     * @param verifier verifier invoked with the emitted events.
     * @return this assertion object.
     */
    public <E extends ModelEvent<?>> ActionAssert<R> assertEmitted(
            Class<E> type, int count, Consumer<List<E>> verifier) {
        var events = getEvents(type);
        if (events.size() != count) {
            throw new AssertionError(
                    "Expected " + count + " event(s) of " + type.getSimpleName() + " but found " + events.size());
        }
        verifier.accept(events);
        return this;
    }

    /**
     * Asserts that no events of {@code type} were emitted.
     *
     * @param <E> event type.
     * @param type event class to inspect.
     * @return this assertion object.
     */
    public <E extends ModelEvent<?>> ActionAssert<R> assertNotEmitted(Class<E> type) {
        var events = getEvents(type);
        if (!events.isEmpty()) {
            throw new AssertionError("Expected no events of " + type.getSimpleName() + " but found " + events.size());
        }
        return this;
    }

    /**
     * Asserts that no model events were emitted.
     *
     * @return this assertion object.
     */
    public ActionAssert<R> assertNoEvents() {
        var total = getEvents(ModelEvent.class).size();
        if (total > 0) {
            throw new AssertionError("Expected no events but found " + total);
        }
        return this;
    }

    // --- No changes ---

    /**
     * Asserts that the action staged no additions, no updates, and emitted no events.
     *
     * @return this assertion object.
     */
    public ActionAssert<R> assertNoChanges() {
        assertNoAdditions();
        assertNoUpdates();
        assertNoEvents();
        return this;
    }

    /**
     * Asserts that the action staged no additions or updates of {@code type}.
     *
     * @param <E> staged entity type.
     * @param type entity class to inspect.
     * @return this assertion object.
     */
    public <E> ActionAssert<R> assertNoChangesOf(Class<E> type) {
        assertNoAdditionsOf(type);
        assertNoUpdatesOf(type);
        return this;
    }

    // --- Result ---

    /**
     * Verifies the action result.
     *
     * @param verifier verifier invoked with the action result.
     * @return this assertion object.
     */
    public ActionAssert<R> assertResult(Consumer<R> verifier) {
        verifier.accept(result);
        return this;
    }

    // --- Raw accessors ---

    /**
     * Returns the raw action result.
     *
     * @return action result.
     */
    public R result() {
        return result;
    }

    /**
     * Returns additions staged for {@code type}.
     *
     * @param <E> staged entity type.
     * @param type entity class to inspect.
     * @return staged additions for the given type.
     */
    public <E> List<E> additions(Class<E> type) {
        return getAdditions(type);
    }

    /**
     * Returns updates staged for {@code type}.
     *
     * @param <E> staged entity type.
     * @param type entity class to inspect.
     * @return staged updates for the given type.
     */
    public <E> List<E> updates(Class<E> type) {
        return getUpdates(type);
    }

    // --- Helpers ---

    private <E> List<E> getAdditions(Class<E> type) {
        var changes = plan.changes().get(type);
        if (changes == null) return List.of();
        return changes.additions().values().stream().map(type::cast).toList();
    }

    private <E> List<E> getUpdates(Class<E> type) {
        var changes = plan.changes().get(type);
        if (changes == null) return List.of();
        return changes.updates().values().stream().map(type::cast).toList();
    }

    @SuppressWarnings("unchecked")
    private <E extends ModelEvent<?>> List<E> getEvents(Class<E> type) {
        var events = new ArrayList<E>();
        for (var changes : plan.changes().values()) {
            collectEvents(changes.additions().values(), type, events);
            collectEvents(changes.updates().values(), type, events);
        }
        return events;
    }

    @SuppressWarnings("unchecked")
    private <E extends ModelEvent<?>> void collectEvents(Collection<?> entities, Class<E> type, List<E> target) {
        for (var entity : entities) {
            if (entity instanceof Model<?, ?, ?> model) {
                for (var event : model.events) {
                    if (type.isInstance(event)) {
                        target.add((E) event);
                    }
                }
            }
        }
    }
}
