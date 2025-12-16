package io.ekbatan.core.action;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.commons.lang3.Validate;

public class ActionRegistry {
    public final Map<Class<? extends Action>, ? extends Action> actions;

    private ActionRegistry(Builder builder) {
        this.actions = builder.actions.build();
    }

    @SuppressWarnings("unchecked")
    public <P, R, A extends Action<P, R>> A get(Class<A> actionClass) {
        Action<?, ?> action = actions.get(actionClass);
        if (action == null) {
            throw new IllegalArgumentException("No action registered for class: " + actionClass.getName());
        }
        return (A) action;
    }

    public static final class Builder {
        private final ImmutableMap.Builder<Class<? extends Action>, Action<?, ?>> actions = ImmutableMap.builder();

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
