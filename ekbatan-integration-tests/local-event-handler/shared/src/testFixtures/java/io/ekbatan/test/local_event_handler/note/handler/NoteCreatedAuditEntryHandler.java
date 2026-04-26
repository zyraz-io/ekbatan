package io.ekbatan.test.local_event_handler.note.handler;

import io.ekbatan.core.repository.AbstractRepository;
import io.ekbatan.core.shard.ShardedUUID;
import io.ekbatan.events.localeventhandler.EventHandler;
import io.ekbatan.test.local_event_handler.audit.models.AuditEntry;
import io.ekbatan.test.local_event_handler.note.models.events.NoteCreatedEvent;
import java.time.Clock;
import java.util.UUID;

public final class NoteCreatedAuditEntryHandler implements EventHandler<NoteCreatedEvent> {

    private final AbstractRepository<AuditEntry, ?, ?, UUID> auditEntryRepository;
    private final Clock clock;

    public NoteCreatedAuditEntryHandler(AbstractRepository<AuditEntry, ?, ?, UUID> auditEntryRepository, Clock clock) {
        this.auditEntryRepository = auditEntryRepository;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "note-created-audit-entry-handler";
    }

    @Override
    public Class<NoteCreatedEvent> eventType() {
        return NoteCreatedEvent.class;
    }

    @Override
    public void handle(NoteCreatedEvent event) {
        // Co-locate the audit entry with the note that triggered it: decode the note's shard
        // from its ID and create the entry on that same shard.
        final var noteShard = ShardedUUID.from(UUID.fromString(event.modelId)).resolveShardIdentifier();
        final var entry = AuditEntry.createAuditEntry(noteShard, event.modelId, event.widgetId, clock.instant())
                .build();
        auditEntryRepository.add(entry);
    }
}
