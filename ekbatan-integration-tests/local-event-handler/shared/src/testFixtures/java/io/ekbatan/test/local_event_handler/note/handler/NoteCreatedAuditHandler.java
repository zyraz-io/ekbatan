package io.ekbatan.test.local_event_handler.note.handler;

import io.ekbatan.events.localeventhandler.EventHandler;
import io.ekbatan.test.local_event_handler.note.models.events.NoteCreatedEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class NoteCreatedAuditHandler implements EventHandler<NoteCreatedEvent> {

    private final List<NoteCreatedEvent> received = new CopyOnWriteArrayList<>();

    @Override
    public String name() {
        return "note-created-audit-handler";
    }

    @Override
    public Class<NoteCreatedEvent> eventType() {
        return NoteCreatedEvent.class;
    }

    @Override
    public void handle(NoteCreatedEvent event) {
        received.add(event);
    }

    public int callCount() {
        return received.size();
    }

    public List<NoteCreatedEvent> received() {
        return List.copyOf(received);
    }
}
