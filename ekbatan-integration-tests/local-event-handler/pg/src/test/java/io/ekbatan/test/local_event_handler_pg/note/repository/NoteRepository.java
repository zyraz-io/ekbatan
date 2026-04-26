package io.ekbatan.test.local_event_handler_pg.note.repository;

import static io.ekbatan.test.local_event_handler.note.models.NoteBuilder.note;
import static io.ekbatan.test.local_event_handler_pg.generated.jooq.public_schema.Tables.NOTES;

import io.ekbatan.core.domain.ShardedId;
import io.ekbatan.core.repository.ModelRepository;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.EmbeddedBitsShardingStrategy;
import io.ekbatan.core.shard.ShardedUUID;
import io.ekbatan.test.local_event_handler.note.models.Note;
import io.ekbatan.test.local_event_handler.note.models.NoteState;
import io.ekbatan.test.local_event_handler_pg.generated.jooq.public_schema.tables.Notes;
import io.ekbatan.test.local_event_handler_pg.generated.jooq.public_schema.tables.records.NotesRecord;
import java.util.UUID;

public class NoteRepository extends ModelRepository<Note, NotesRecord, Notes, UUID> {

    public NoteRepository(DatabaseRegistry databaseRegistry) {
        super(Note.class, NOTES, NOTES.ID, databaseRegistry, new EmbeddedBitsShardingStrategy());
    }

    @Override
    public Note fromRecord(NotesRecord record) {
        return note().id(ShardedId.of(Note.class, ShardedUUID.from(record.getId())))
                .version(record.getVersion())
                .state(NoteState.valueOf(record.getState()))
                .widgetId(record.getWidgetId())
                .text(record.getText())
                .createdDate(record.getCreatedDate())
                .updatedDate(record.getUpdatedDate())
                .build();
    }

    @Override
    public NotesRecord toRecord(Note model) {
        return new NotesRecord(
                model.id.getValue(),
                model.version,
                model.state.name(),
                model.widgetId,
                model.text,
                model.createdDate,
                model.updatedDate);
    }
}
