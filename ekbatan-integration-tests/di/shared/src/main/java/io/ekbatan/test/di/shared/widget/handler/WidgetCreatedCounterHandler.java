package io.ekbatan.test.di.shared.widget.handler;

import io.ekbatan.di.EkbatanEventHandler;
import io.ekbatan.events.localeventhandler.EventHandler;
import io.ekbatan.test.di.shared.widget.models.events.WidgetCreatedEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records every {@link WidgetCreatedEvent} the local-event-handler dispatch delivers. Used by
 * each DI-flavor integration test to verify that the framework's auto-config / extension wires
 * the fan-out and handling jobs end to end: action persists → fan-out writes notifications →
 * handling job picks them up and invokes this handler.
 *
 * <p>Both fields are concurrency-safe because the handling job invokes handlers from a
 * scheduler-owned thread; the test thread reads them via assertions.
 */
@EkbatanEventHandler
public class WidgetCreatedCounterHandler implements EventHandler<WidgetCreatedEvent> {

    private final AtomicInteger callCount = new AtomicInteger();
    private final List<String> handledNames = new CopyOnWriteArrayList<>();

    @Override
    public String name() {
        return "widget-created-counter-handler";
    }

    @Override
    public Class<WidgetCreatedEvent> eventType() {
        return WidgetCreatedEvent.class;
    }

    @Override
    public void handle(WidgetCreatedEvent event) {
        handledNames.add(event.name);
        callCount.incrementAndGet();
    }

    public int callCount() {
        return callCount.get();
    }

    public List<String> handledNames() {
        return List.copyOf(handledNames);
    }
}
