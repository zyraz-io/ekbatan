package io.ekbatan.core.action;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.Principal;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActionRegistryBuilderTest {

    @Test
    void withActions_indexes_each_action_by_its_concrete_class() {
        var clock = Clock.systemUTC();
        var widgetAction = new WidgetCreateAction(clock);
        var noteAction = new NoteCreateAction(clock);

        var registry = ActionRegistry.Builder.actionRegistry()
                .withActions(List.of(widgetAction, noteAction))
                .build();

        assertThat(registry.get(WidgetCreateAction.class)).isSameAs(widgetAction);
        assertThat(registry.get(NoteCreateAction.class)).isSameAs(noteAction);
    }

    @Test
    void withActions_returns_the_same_singleton_on_every_call() {
        var widgetAction = new WidgetCreateAction(Clock.systemUTC());
        var registry = ActionRegistry.Builder.actionRegistry()
                .withActions(List.of(widgetAction))
                .build();

        var first = registry.get(WidgetCreateAction.class);
        var second = registry.get(WidgetCreateAction.class);

        assertThat(first).isSameAs(second);
        assertThat(first).isSameAs(widgetAction);
    }

    static final class WidgetCreateAction extends Action<String, String> {
        WidgetCreateAction(Clock clock) {
            super(clock);
        }

        @Override
        protected String perform(Principal principal, String params) {
            return params;
        }
    }

    static final class NoteCreateAction extends Action<String, String> {
        NoteCreateAction(Clock clock) {
            super(clock);
        }

        @Override
        protected String perform(Principal principal, String params) {
            return params;
        }
    }
}
