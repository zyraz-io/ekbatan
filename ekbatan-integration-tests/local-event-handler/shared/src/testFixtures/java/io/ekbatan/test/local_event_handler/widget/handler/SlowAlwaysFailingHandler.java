package io.ekbatan.test.local_event_handler.widget.handler;

import io.ekbatan.events.localeventhandler.EventEnvelope;
import io.ekbatan.events.localeventhandler.EventHandler;
import io.ekbatan.test.local_event_handler.widget.models.events.WidgetCreatedEvent;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public final class SlowAlwaysFailingHandler implements EventHandler<WidgetCreatedEvent> {

    private final Duration sleep;
    private final Runnable afterDelay;
    private final AtomicInteger callCount = new AtomicInteger();

    public SlowAlwaysFailingHandler(Duration sleep) {
        this(sleep, () -> {});
    }

    public SlowAlwaysFailingHandler(Duration sleep, Runnable afterDelay) {
        this.sleep = sleep;
        this.afterDelay = afterDelay;
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
    public void handle(EventEnvelope<WidgetCreatedEvent> envelope) throws InterruptedException {
        Thread.sleep(sleep.toMillis());
        afterDelay.run();
        callCount.incrementAndGet();
        throw new RuntimeException("intentional failure");
    }

    public int callCount() {
        return callCount.get();
    }
}
