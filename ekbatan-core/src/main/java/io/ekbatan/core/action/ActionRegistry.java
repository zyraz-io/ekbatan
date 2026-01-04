package io.ekbatan.core.action;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.commons.lang3.Validate;

public class ActionRegistry {
    public final Map<Class<? extends Action<?, ?>>, Supplier<? extends Action<?, ?>>> actions;

    private ActionRegistry(Builder builder) {
        this.actions = builder.actions.build();
    }

    @SuppressWarnings("unchecked")
    public <P, R, A extends Action<P, R>> A get(Class<A> actionClass) {
        final var actionSupplier = Validate.notNull(actions.get(actionClass), "No action registered for class");

        return (A) actionSupplier.get();
    }

    public static final class Builder {
        private final ImmutableMap.Builder<Class<? extends Action<?, ?>>, Supplier<? extends Action<?, ?>>> actions =
                ImmutableMap.builder();

        private Builder() {}

        public static Builder actionRegistry() {
            return new Builder();
        }

        public <P, R, A extends Action<P, R>> Builder withAction(Class<A> actionClass, Supplier<A> actionSupplier) {
            Validate.notNull(actionClass, "actionClass cannot be null");
            Validate.notNull(actionSupplier, "actionSupplier cannot be null");
            actions.put(actionClass, actionSupplier);
            return this;
        }

        public ActionRegistry build() {
            return new ActionRegistry(this);
        }
    }
}
