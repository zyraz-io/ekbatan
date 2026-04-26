package io.ekbatan.test.local_event_handler.note.models;

import static io.ekbatan.test.local_event_handler.note.models.NoteState.ACTIVE;

import io.ekbatan.core.domain.Model;
import io.ekbatan.core.domain.ShardedId;
import io.ekbatan.core.processor.AutoBuilder;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.test.local_event_handler.note.models.events.NoteCreatedEvent;
import java.time.Instant;
import org.apache.commons.lang3.Validate;

@AutoBuilder
public final class Note extends Model<Note, ShardedId<Note>, NoteState> {

    public final String widgetId;
    public final String text;

    Note(NoteBuilder builder) {
        super(builder);
        this.widgetId = Validate.notBlank(builder.widgetId, "widgetId cannot be blank");
        this.text = Validate.notBlank(builder.text, "text cannot be blank");
    }

    public static NoteBuilder createNote(ShardIdentifier shard, String widgetId, String text, Instant createdDate) {
        final var id = ShardedId.generate(Note.class, shard);
        return NoteBuilder.note()
                .id(id)
                .state(ACTIVE)
                .widgetId(widgetId)
                .text(text)
                .createdDate(createdDate)
                .withInitialVersion()
                .withEvent(new NoteCreatedEvent(id, widgetId, text));
    }

    @Override
    public NoteBuilder copy() {
        return NoteBuilder.note().copyBase(this).widgetId(widgetId).text(text);
    }
}
