package io.ekbatan.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.events.localeventhandler.EventHandler;
import java.security.Principal;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegistryAssemblerTest {

    @Test
    void actionRegistry_indexes_each_action_singleton_by_its_concrete_class() {
        // GIVEN — singleton instances of each action (mirrors what the Spring auto-config
        // produces by calling AutowireCapableBeanFactory.createBean(...) once at startup).
        var clock = Clock.systemUTC();
        var widgetAction = new WidgetCreateAction(clock);
        var noteAction = new NoteCreateAction(clock);

        // WHEN
        var registry = RegistryAssembler.actionRegistry(List.of(widgetAction, noteAction));

        // THEN — lookup by class returns the registered singleton
        assertThat(registry.get(WidgetCreateAction.class)).isSameAs(widgetAction);
        assertThat(registry.get(NoteCreateAction.class)).isSameAs(noteAction);
    }

    @Test
    void actionRegistry_returns_the_same_singleton_on_every_call() {
        // GIVEN
        var widgetAction = new WidgetCreateAction(Clock.systemUTC());
        var registry = RegistryAssembler.actionRegistry(List.of(widgetAction));

        // WHEN — resolve twice
        var first = registry.get(WidgetCreateAction.class);
        var second = registry.get(WidgetCreateAction.class);

        // THEN — same instance both times (singleton semantics)
        assertThat(first).isSameAs(second);
        assertThat(first).isSameAs(widgetAction);
    }

    @Test
    void eventHandlerRegistry_indexes_handlers_by_name_and_event_type() {
        // GIVEN
        var h1 = new TestHandler("alpha", FooEvent.class);
        var h2 = new TestHandler("beta", FooEvent.class);
        var h3 = new TestHandler("gamma", BarEvent.class);

        // WHEN
        var registry = RegistryAssembler.eventHandlerRegistry(List.of(h1, h2, h3));

        // THEN — name-based lookup
        assertThat(registry.handlerFor("alpha")).isSameAs(h1);
        assertThat(registry.handlerFor("beta")).isSameAs(h2);
        assertThat(registry.handlerFor("gamma")).isSameAs(h3);
        assertThat(registry.handlerFor("missing")).isNull();

        // AND — event-type subscription lookup
        assertThat(registry.subscribersFor("FooEvent")).containsExactlyInAnyOrder("alpha", "beta");
        assertThat(registry.subscribersFor("BarEvent")).containsExactly("gamma");
        assertThat(registry.subscribersFor("UnknownEvent")).isEmpty();
    }

    // ---------- fixtures ----------

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

    static final class TestHandler implements EventHandler<TestModelEvent> {
        private final String name;
        private final Class<? extends TestModelEvent> eventType;

        TestHandler(String name, Class<? extends TestModelEvent> eventType) {
            this.name = name;
            this.eventType = eventType;
        }

        @Override
        public String name() {
            return name;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Class<TestModelEvent> eventType() {
            return (Class<TestModelEvent>) eventType;
        }

        @Override
        public void handle(TestModelEvent event) {}
    }

    static class TestModelEvent extends ModelEvent<TestPlaceholder> {
        protected TestModelEvent(String modelId) {
            super(modelId, TestPlaceholder.class);
        }
    }

    static final class FooEvent extends TestModelEvent {
        FooEvent() {
            super("foo-id");
        }
    }

    static final class BarEvent extends TestModelEvent {
        BarEvent() {
            super("bar-id");
        }
    }

    /** Phantom type parameter for {@link TestModelEvent} — not instantiated. */
    static final class TestPlaceholder {}
}
