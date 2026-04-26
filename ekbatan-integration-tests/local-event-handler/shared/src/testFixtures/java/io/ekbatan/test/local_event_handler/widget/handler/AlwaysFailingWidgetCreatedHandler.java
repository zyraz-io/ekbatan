package io.ekbatan.test.local_event_handler.widget.handler;

import io.ekbatan.events.localeventhandler.EventHandler;
import io.ekbatan.test.local_event_handler.widget.models.events.WidgetCreatedEvent;
import java.util.concurrent.atomic.AtomicInteger;

public final class AlwaysFailingWidgetCreatedHandler implements EventHandler<WidgetCreatedEvent> {

    private final AtomicInteger callCount = new AtomicInteger();

    @Override
    public String name() {
        return "always-failing-widget-created-handler";
    }

    @Override
    public Class<WidgetCreatedEvent> eventType() {
        return WidgetCreatedEvent.class;
    }

    @Override
    public void handle(WidgetCreatedEvent event) {
        callCount.incrementAndGet();
        throw new RuntimeException("intentional failure");
    }

    public int callCount() {
        return callCount.get();
    }
}
