package io.ekbatan.test.local_event_handler.widget.handler;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.shard.ShardedUUID;
import io.ekbatan.events.localeventhandler.EventHandler;
import io.ekbatan.test.local_event_handler.note.action.NoteCreateAction;
import io.ekbatan.test.local_event_handler.widget.models.events.WidgetCreatedEvent;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class WidgetCreatedAutoNoteHandler implements EventHandler<WidgetCreatedEvent> {

    private final ActionExecutor actionExecutor;
    private final AtomicInteger callCount = new AtomicInteger();

    public WidgetCreatedAutoNoteHandler(ActionExecutor actionExecutor) {
        this.actionExecutor = actionExecutor;
    }

    @Override
    public String name() {
        return "widget-created-auto-note-handler";
    }

    @Override
    public Class<WidgetCreatedEvent> eventType() {
        return WidgetCreatedEvent.class;
    }

    @Override
    public void handle(WidgetCreatedEvent event) throws Exception {
        // Co-locate the auto-note with the widget that triggered it: decode the widget's shard
        // from its ID (a ShardedUUID with shard bits embedded) and create the note on that
        // same shard.
        final var widgetShard = ShardedUUID.from(UUID.fromString(event.modelId)).resolveShardIdentifier();
        actionExecutor.execute(
                () -> "auto-note",
                NoteCreateAction.class,
                new NoteCreateAction.Params(widgetShard, event.modelId, "auto-note for widget " + event.name));
        callCount.incrementAndGet();
    }

    public int callCount() {
        return callCount.get();
    }
}
