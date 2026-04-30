package io.ekbatan.core.action;

import com.google.common.collect.ImmutableMap;
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

    public final Map<Class<? extends Action<?, ?>>, Action<?, ?>> actions;

    private ActionRegistry(Builder builder) {
        this.actions = builder.actions.build();
    }

    @SuppressWarnings("unchecked")
    public <P, R, A extends Action<P, R>> A get(Class<A> actionClass) {
        return (A) Validate.notNull(actions.get(actionClass), "No action registered for class");
    }

    public static final class Builder {

        private final ImmutableMap.Builder<Class<? extends Action<?, ?>>, Action<?, ?>> actions =
                ImmutableMap.builder();

        private Builder() {}

        public static Builder actionRegistry() {
            return new Builder();
        }

        public <P, R, A extends Action<P, R>> Builder withAction(Class<A> actionClass, A action) {
            Validate.notNull(actionClass, "actionClass cannot be null");
            Validate.notNull(action, "action cannot be null");
            actions.put(actionClass, action);
            return this;
        }

        public ActionRegistry build() {
            return new ActionRegistry(this);
        }
    }
}
