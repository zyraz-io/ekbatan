package io.ekbatan.test.local_event_handler.widget.handler;

import io.ekbatan.events.localeventhandler.EventHandler;
import io.ekbatan.test.local_event_handler.widget.models.events.WidgetCreatedEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class WidgetCreatedEmailHandler implements EventHandler<WidgetCreatedEvent> {

    private final List<WidgetCreatedEvent> received = new CopyOnWriteArrayList<>();

    @Override
    public String name() {
        return "widget-created-email-handler";
    }

    @Override
    public Class<WidgetCreatedEvent> eventType() {
        return WidgetCreatedEvent.class;
    }

    @Override
    public void handle(WidgetCreatedEvent event) {
        received.add(event);
    }

    public int callCount() {
        return received.size();
    }

    public List<WidgetCreatedEvent> received() {
        return List.copyOf(received);
    }
}
