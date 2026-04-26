package io.ekbatan.test.local_event_handler_mysql.audit.repository;

import static io.ekbatan.test.local_event_handler.audit.models.AuditEntryBuilder.auditEntry;
import static io.ekbatan.test.local_event_handler_mysql.generated.jooq.Tables.AUDIT_ENTRIES;

import io.ekbatan.core.domain.GenericState;
import io.ekbatan.core.domain.ShardedId;
import io.ekbatan.core.repository.EntityRepository;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.EmbeddedBitsShardingStrategy;
import io.ekbatan.core.shard.ShardedUUID;
import io.ekbatan.test.local_event_handler.audit.models.AuditEntry;
import io.ekbatan.test.local_event_handler_mysql.generated.jooq.tables.AuditEntries;
import io.ekbatan.test.local_event_handler_mysql.generated.jooq.tables.records.AuditEntriesRecord;
import java.util.UUID;

public class AuditEntryRepository extends EntityRepository<AuditEntry, AuditEntriesRecord, AuditEntries, UUID> {

    public AuditEntryRepository(DatabaseRegistry databaseRegistry) {
        super(AuditEntry.class, AUDIT_ENTRIES, AUDIT_ENTRIES.ID, databaseRegistry, new EmbeddedBitsShardingStrategy());
    }

    @Override
    public AuditEntry fromRecord(AuditEntriesRecord record) {
        return auditEntry()
                .id(ShardedId.of(AuditEntry.class, ShardedUUID.from(record.getId())))
                .version(record.getVersion())
                .state(GenericState.valueOf(record.getState()))
                .noteId(record.getNoteId())
                .widgetId(record.getWidgetId())
                .createdDate(record.getCreatedDate())
                .build();
    }

    @Override
    public AuditEntriesRecord toRecord(AuditEntry model) {
        return new AuditEntriesRecord(
                model.id.getValue(),
                model.version,
                model.state.name(),
                model.noteId,
                model.widgetId,
                model.createdDate);
    }
}
