package io.ekbatan.core.action;

import static io.ekbatan.core.action.ActionRegistry.Builder.actionRegistry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ekbatan.core.time.VirtualClock;
import org.junit.jupiter.api.Test;

class ActionRegistryTest {

    @Test
    void build_creates_empty_registry() {
        // WHEN
        var registry = actionRegistry().build();

        // THEN
        assertThat(registry.actions).isEmpty();
    }

    @Test
    void withAction_registers_action_supplier() {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN
        var registry = actionRegistry()
                .withAction(CreateAction.class, new CreateAction(clock))
                .build();

        // THEN
        assertThat(registry.actions).hasSize(1);
    }

    @Test
    void get_returns_new_action_instance() {
        // GIVEN
        var clock = new VirtualClock();
        var registry = actionRegistry()
                .withAction(CreateAction.class, new CreateAction(clock))
                .build();

        // WHEN
        var action = registry.get(CreateAction.class);

        // THEN
        assertThat(action).isNotNull();
        assertThat(action).isInstanceOf(CreateAction.class);
    }

    @Test
    void get_returns_the_registered_singleton_on_every_call() {
        // GIVEN — Action is a singleton: registered once, shared across all executions.
        // Per-call mutable state lives in ActionPlan via ScopedValue, not on the instance.
        var clock = new VirtualClock();
        var action = new CreateAction(clock);
        var registry = actionRegistry().withAction(CreateAction.class, action).build();

        // WHEN
        var first = registry.get(CreateAction.class);
        var second = registry.get(CreateAction.class);

        // THEN
        assertThat(first).isSameAs(second);
        assertThat(first).isSameAs(action);
    }

    @Test
    void get_throws_for_unregistered_action() {
        // GIVEN
        var registry = actionRegistry().build();

        // WHEN / THEN
        assertThatThrownBy(() -> registry.get(CreateAction.class))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("No action registered for class");
    }

    @Test
    void withAction_rejects_null_class() {
        // GIVEN / WHEN / THEN
        var action = new CreateAction(new VirtualClock());
        assertThatThrownBy(() -> actionRegistry().withAction(null, action))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actionClass cannot be null");
    }

    @Test
    void withAction_rejects_null_action() {
        // GIVEN / WHEN / THEN
        assertThatThrownBy(() -> actionRegistry().withAction(CreateAction.class, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("action cannot be null");
    }

    @Test
    void multiple_actions_registered() {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN
        var registry = actionRegistry()
                .withAction(CreateAction.class, new CreateAction(clock))
                .withAction(FailingAction.class, new FailingAction(clock))
                .build();

        // THEN
        assertThat(registry.actions).hasSize(2);
        assertThat(registry.get(CreateAction.class)).isInstanceOf(CreateAction.class);
        assertThat(registry.get(FailingAction.class)).isInstanceOf(FailingAction.class);
    }
}
