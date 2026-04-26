package io.ekbatan.test.local_event_handler.widget.handler;

import io.ekbatan.events.localeventhandler.EventHandler;
import io.ekbatan.test.local_event_handler.widget.models.events.WidgetCreatedEvent;
import java.util.concurrent.atomic.AtomicInteger;

public final class FlakyWidgetCreatedHandler implements EventHandler<WidgetCreatedEvent> {

    private final int failFirstN;
    private final AtomicInteger callCount = new AtomicInteger();

    public FlakyWidgetCreatedHandler(int failFirstN) {
        this.failFirstN = failFirstN;
    }

    @Override
    public String name() {
        return "flaky-widget-created-handler";
    }

    @Override
    public Class<WidgetCreatedEvent> eventType() {
        return WidgetCreatedEvent.class;
    }

    @Override
    public void handle(WidgetCreatedEvent event) {
        final int attempt = callCount.incrementAndGet();
        if (attempt <= failFirstN) {
            throw new RuntimeException("flaky failure on attempt " + attempt);
        }
    }

    public int callCount() {
        return callCount.get();
    }
}
