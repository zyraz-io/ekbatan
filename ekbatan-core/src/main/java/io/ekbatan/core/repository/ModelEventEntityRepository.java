package io.ekbatan.core.repository;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ekbatan.core.domain.event.ModelEventEntity;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.persistence.jooq.converter.InstantConverter;
import io.ekbatan.core.persistence.jooq.converter.ObjectNodeConverter;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

public class ModelEventEntityRepository {

    private final TransactionManager transactionManager;
    private final DSLContext db;

    private static final org.jooq.Table<?> TABLE = DSL.table(DSL.name("eventlog", "model_events"));
    private static final Field<UUID> ID = DSL.field(DSL.name("eventlog", "model_events", "id"), UUID.class);
    private static final Field<UUID> ACTION_ID =
            DSL.field(DSL.name("eventlog", "model_events", "action_id"), UUID.class);
    private static final Field<String> MODEL_ID =
            DSL.field(DSL.name("eventlog", "model_events", "model_id"), String.class);
    private static final Field<String> MODEL_TYPE =
            DSL.field(DSL.name("eventlog", "model_events", "model_type"), String.class);
    private static final Field<String> EVENT_TYPE =
            DSL.field(DSL.name("eventlog", "model_events", "event_type"), String.class);
    private static final Field<ObjectNode> PAYLOAD = DSL.field(
            DSL.name("eventlog", "model_events", "payload"),
            SQLDataType.JSONB.asConvertedDataType(new ObjectNodeConverter()));
    private static final Field<Instant> EVENT_DATA = DSL.field(
            DSL.name("eventlog", "model_events", "event_data"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));

    public ModelEventEntityRepository(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
        this.db = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), transactionManager.dialect);
    }

    private DSLContext txDbElseDb() {
        return transactionManager.currentTransactionDbContext().orElse(db);
    }

    public void addAllNoResult(Collection<ModelEventEntity> entities) {
        if (entities.isEmpty()) return;
        final var insert =
                txDbElseDb().insertInto(TABLE, ID, ACTION_ID, MODEL_ID, MODEL_TYPE, EVENT_TYPE, PAYLOAD, EVENT_DATA);
        for (var entity : entities) {
            insert.values(
                    entity.id,
                    entity.actionId,
                    entity.modelId,
                    entity.modelType,
                    entity.eventType,
                    entity.payload,
                    entity.eventData);
        }
        insert.execute();
    }
}
