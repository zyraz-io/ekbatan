package io.ekbatan.test.local_event_handler.widget.handler;

import io.ekbatan.events.localeventhandler.EventHandler;
import io.ekbatan.test.local_event_handler.widget.models.events.WidgetCreatedEvent;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public final class SlowAlwaysFailingHandler implements EventHandler<WidgetCreatedEvent> {

    private final Duration sleep;
    private final AtomicInteger callCount = new AtomicInteger();

    public SlowAlwaysFailingHandler(Duration sleep) {
        this.sleep = sleep;
    }

    @Override
    public String name() {
        return "slow-always-failing-widget-created-handler";
    }

    @Override
    public Class<WidgetCreatedEvent> eventType() {
        return WidgetCreatedEvent.class;
    }

    @Override
    public void handle(WidgetCreatedEvent event) throws InterruptedException {
        Thread.sleep(sleep.toMillis());
        callCount.incrementAndGet();
        throw new RuntimeException("intentional failure");
    }

    public int callCount() {
        return callCount.get();
    }
}
