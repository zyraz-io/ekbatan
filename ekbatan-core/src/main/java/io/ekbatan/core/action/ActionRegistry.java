package io.ekbatan.core.action;

import com.google.common.collect.ImmutableMap;
import java.util.Collection;
import java.util.Map;
import org.apache.commons.lang3.Validate;

/**
 * Maps each {@code @EkbatanAction} subclass to its singleton instance. {@link Action}s are
 * created exactly once at startup (with constructor dependencies wired by the host DI
 * container), shared across every {@code ActionExecutor.execute(...)} call, and resolved here
 * by the executor at lookup time.
 *
 * <p>Per-execution state lives elsewhere — see {@link Action#runIn(ActionPlan, java.security.Principal, Object)}
 * — so storing the instance directly (rather than a {@code Supplier}) is correct.
 */
public class ActionRegistry {

    /** Immutable map from action class to its singleton instance; populated at startup, read by {@code ActionExecutor}. */
    public final Map<Class<? extends Action<?, ?>>, Action<?, ?>> actions;

    private ActionRegistry(Builder builder) {
        this.actions = builder.actions.build();
    }

    /**
     * Resolves the singleton instance for an action class.
     *
     * @param actionClass the action class to look up.
     * @param <P> the action's parameter type.
     * @param <R> the action's result type.
     * @param <A> the concrete action type.
     * @return the registered instance.
     * @throws NullPointerException if no instance is registered for {@code actionClass}.
     */
    @SuppressWarnings("unchecked")
    public <P, R, A extends Action<P, R>> A get(Class<A> actionClass) {
        return (A) Validate.notNull(actions.get(actionClass), "No action registered for class");
    }

    /** Fluent builder for {@link ActionRegistry}. Obtain via {@link #actionRegistry()}. */
    public static final class Builder {

        private final ImmutableMap.Builder<Class<? extends Action<?, ?>>, Action<?, ?>> actions =
                ImmutableMap.builder();

        private Builder() {}

        /** {@return a fresh builder for {@link ActionRegistry}} */
        public static Builder actionRegistry() {
            return new Builder();
        }

        /**
         * Registers a single action instance against its class.
         *
         * @param actionClass the action class (used as the lookup key).
         * @param action the singleton instance.
         * @param <P> the action's parameter type.
         * @param <R> the action's result type.
         * @param <A> the concrete action type.
         * @return this builder, for chaining.
         */
        public <P, R, A extends Action<P, R>> Builder withAction(Class<A> actionClass, A action) {
            Validate.notNull(actionClass, "actionClass cannot be null");
            Validate.notNull(action, "action cannot be null");
            actions.put(actionClass, action);
            return this;
        }

        /**
         * Registers a batch of action instances; each is keyed by its concrete runtime class.
         *
         * @param actions the action instances; their {@code getClass()} provides the registry key.
         * @return this builder, for chaining.
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Builder withActions(Collection<? extends Action<?, ?>> actions) {
            Validate.notNull(actions, "actions cannot be null");
            for (var action : actions) {
                Class cls = action.getClass();
                withAction(cls, action);
            }
            return this;
        }

        /** {@return a built {@link ActionRegistry}} */
        public ActionRegistry build() {
            return new ActionRegistry(this);
        }
    }
}
