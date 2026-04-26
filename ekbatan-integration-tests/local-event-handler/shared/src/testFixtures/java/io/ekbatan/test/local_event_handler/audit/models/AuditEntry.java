package io.ekbatan.test.local_event_handler.audit.models;

import io.ekbatan.core.domain.Entity;
import io.ekbatan.core.domain.GenericState;
import io.ekbatan.core.domain.ShardedId;
import io.ekbatan.core.processor.AutoBuilder;
import io.ekbatan.core.shard.ShardIdentifier;
import java.time.Instant;
import org.apache.commons.lang3.Validate;

@AutoBuilder
public final class AuditEntry extends Entity<AuditEntry, ShardedId<AuditEntry>, GenericState> {

    public final String noteId;
    public final String widgetId;
    public final Instant createdDate;

    AuditEntry(AuditEntryBuilder builder) {
        super(builder);
        this.noteId = Validate.notBlank(builder.noteId, "noteId cannot be blank");
        this.widgetId = Validate.notBlank(builder.widgetId, "widgetId cannot be blank");
        this.createdDate = Validate.notNull(builder.createdDate, "createdDate cannot be null");
    }

    public static AuditEntryBuilder createAuditEntry(
            ShardIdentifier shard, String noteId, String widgetId, Instant createdDate) {
        return AuditEntryBuilder.auditEntry()
                .id(ShardedId.generate(AuditEntry.class, shard))
                .state(GenericState.ACTIVE)
                .noteId(noteId)
                .widgetId(widgetId)
                .createdDate(createdDate)
                .withInitialVersion();
    }

    @Override
    public AuditEntryBuilder copy() {
        return AuditEntryBuilder.auditEntry()
                .copyBase(this)
                .noteId(noteId)
                .widgetId(widgetId)
                .createdDate(createdDate);
    }
}
