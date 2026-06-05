package io.ekbatan.events.localeventhandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ekbatan.core.domain.ModelEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EventHandlerRegistryBuilderTest {

    @Test
    void withHandlers_indexes_handlers_by_name_and_event_type() {
        var h1 = new TestHandler("alpha", FooEvent.class);
        var h2 = new TestHandler("beta", FooEvent.class);
        var h3 = new TestHandler("gamma", BarEvent.class);

        var registry = EventHandlerRegistry.eventHandlerRegistry()
                .withHandlers(List.of(h1, h2, h3))
                .build();

        assertThat(registry.handlerFor("alpha")).isSameAs(h1);
        assertThat(registry.handlerFor("beta")).isSameAs(h2);
        assertThat(registry.handlerFor("gamma")).isSameAs(h3);
        assertThat(registry.handlerFor("missing")).isNull();

        assertThat(registry.subscribersFor("FooEvent")).containsExactlyInAnyOrder("alpha", "beta");
        assertThat(registry.subscribersFor("BarEvent")).containsExactly("gamma");
        assertThat(registry.subscribersFor("UnknownEvent")).isEmpty();
        assertThat(registry.handledEventTypes()).containsExactlyInAnyOrder("FooEvent", "BarEvent");
    }

    @Test
    void withHandler_resolves_aliases_without_using_them_for_fanout() {
        var handler = new TestHandler("alpha", FooEvent.class, Set.of("old-alpha", "older-alpha"));

        var registry =
                EventHandlerRegistry.eventHandlerRegistry().withHandler(handler).build();

        assertThat(registry.handlerFor("alpha")).isSameAs(handler);
        assertThat(registry.handlerFor("old-alpha")).isSameAs(handler);
        assertThat(registry.handlerFor("older-alpha")).isSameAs(handler);
        assertThat(registry.subscribersFor("FooEvent")).containsExactly("alpha");
    }

    @Test
    void withHandler_rejects_alias_that_duplicates_canonical_name() {
        var handler = new TestHandler("alpha", FooEvent.class, Set.of("alpha"));

        assertThatThrownBy(() -> EventHandlerRegistry.eventHandlerRegistry().withHandler(handler))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Handler alias duplicates canonical name: alpha");
    }

    @Test
    void withHandler_rejects_alias_collision_with_existing_lookup_name() {
        var first = new TestHandler("alpha", FooEvent.class, Set.of("old-alpha"));
        var second = new TestHandler("beta", FooEvent.class, Set.of("old-alpha"));

        assertThatThrownBy(() -> EventHandlerRegistry.eventHandlerRegistry()
                        .withHandler(first)
                        .withHandler(second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Handler name or alias already registered: old-alpha");
    }

    @Test
    void withHandler_rejects_name_collision_with_existing_alias() {
        var first = new TestHandler("alpha", FooEvent.class, Set.of("old-alpha"));
        var second = new TestHandler("old-alpha", FooEvent.class);

        assertThatThrownBy(() -> EventHandlerRegistry.eventHandlerRegistry()
                        .withHandler(first)
                        .withHandler(second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Handler name or alias already registered: old-alpha");
    }

    @Test
    void withHandler_rejects_different_event_classes_with_same_simple_name() {
        var first = new TestHandler("first", FirstNamespace.CollidingEvent.class);
        var second = new TestHandler("second", SecondNamespace.CollidingEvent.class);

        assertThatThrownBy(() -> EventHandlerRegistry.eventHandlerRegistry()
                        .withHandler(first)
                        .withHandler(second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Event type simple name collision: CollidingEvent")
                .hasMessageContaining(FirstNamespace.CollidingEvent.class.getName())
                .hasMessageContaining(SecondNamespace.CollidingEvent.class.getName());
    }

    static final class TestHandler implements EventHandler<TestModelEvent> {
        private final String name;
        private final Class<? extends TestModelEvent> eventType;
        private final Set<String> aliases;

        TestHandler(String name, Class<? extends TestModelEvent> eventType) {
            this(name, eventType, Set.of());
        }

        TestHandler(String name, Class<? extends TestModelEvent> eventType, Set<String> aliases) {
            this.name = name;
            this.eventType = eventType;
            this.aliases = aliases;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Set<String> aliases() {
            return aliases;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Class<TestModelEvent> eventType() {
            return (Class<TestModelEvent>) eventType;
        }

        @Override
        public void handle(EventEnvelope<TestModelEvent> envelope) {}
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

    static final class FirstNamespace {
        static final class CollidingEvent extends TestModelEvent {
            CollidingEvent() {
                super("first-id");
            }
        }
    }

    static final class SecondNamespace {
        static final class CollidingEvent extends TestModelEvent {
            CollidingEvent() {
                super("second-id");
            }
        }
    }

    static final class TestPlaceholder {}
}
