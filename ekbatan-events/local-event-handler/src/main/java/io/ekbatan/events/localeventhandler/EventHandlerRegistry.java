package io.ekbatan.events.localeventhandler;

import io.ekbatan.core.domain.ModelEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.Validate;

/**
 * The user-facing registry of {@link EventHandler}s. Built up via {@link Builder} at
 * application startup, then handed to both {@code EventFanoutJob} and
 * {@code EventHandlingJob}.
 *
 * <p>Two readers consume this from the framework's side:
 * <ul>
 *   <li>{@code EventFanoutJob} calls {@link #subscribersFor(String)} to learn which
 *       handler names need notification rows for a given event type.</li>
 *   <li>{@code EventHandlingJob} calls {@link #handlerFor(String)} to route a claimed
 *       delivery to the matching handler instance.</li>
 * </ul>
 *
 * <p>Both methods are public for cross-package access from the framework's job classes;
 * application code should not need to call them directly — they are framework hooks, not
 * part of the user-facing API.
 */
public final class EventHandlerRegistry {

    private final Map<String, EventHandler<?>> handlersByName;
    private final Map<String, List<String>> namesByEventType;

    private EventHandlerRegistry(Builder builder) {
        this.handlersByName = Map.copyOf(builder.handlersByName);
        final var namesByEventType = new LinkedHashMap<String, List<String>>();
        builder.namesByEventType.forEach((type, names) -> namesByEventType.put(type, List.copyOf(names)));
        this.namesByEventType = Collections.unmodifiableMap(namesByEventType);
    }

    public static Builder eventHandlerRegistry() {
        return new Builder();
    }

    /**
     * Framework hook used by {@code EventFanoutJob}: the cluster-stable names of every
     * handler subscribed to {@code eventTypeSimpleName}. Empty list if none.
     */
    public List<String> subscribersFor(String eventTypeSimpleName) {
        return namesByEventType.getOrDefault(eventTypeSimpleName, List.of());
    }

    /**
     * Framework hook used by {@code EventHandlingJob}: the handler instance registered
     * under {@code handlerName}, or {@code null} if no such handler is registered (e.g.
     * a notification row exists for a handler that has since been removed from the code).
     */
    public EventHandler<?> handlerFor(String handlerName) {
        return handlersByName.get(handlerName);
    }

    public static final class Builder {

        private final Map<String, EventHandler<?>> handlersByName = new HashMap<>();
        private final Map<String, List<String>> namesByEventType = new LinkedHashMap<>();

        private Builder() {}

        public Builder withHandler(EventHandler<? extends ModelEvent<?>> handler) {
            Validate.notNull(handler, "handler cannot be null");
            final var name = Validate.notBlank(handler.name(), "handler.name() cannot be blank");
            final var eventType = Validate.notNull(handler.eventType(), "handler.eventType() cannot be null");

            if (handlersByName.containsKey(name)) {
                throw new IllegalArgumentException(
                        "Handler name already registered: " + name + " (handlers must have unique names)");
            }
            handlersByName.put(name, handler);
            namesByEventType
                    .computeIfAbsent(eventType.getSimpleName(), _ -> new ArrayList<>())
                    .add(name);
            return this;
        }

        public EventHandlerRegistry build() {
            return new EventHandlerRegistry(this);
        }
    }
}
