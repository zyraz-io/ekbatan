package io.ekbatan.core.repository;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ekbatan.core.domain.event.ActionEventEntity;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.persistence.jooq.converter.InstantConverter;
import io.ekbatan.core.persistence.jooq.converter.ObjectNodeConverter;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

public class ActionEventEntityRepository {

    private final TransactionManager transactionManager;
    private final DSLContext db;

    private static final org.jooq.Table<?> TABLE = DSL.table(DSL.name("eventlog", "action_events"));
    private static final Field<UUID> ID = DSL.field(DSL.name("eventlog", "action_events", "id"), UUID.class);
    private static final Field<Instant> STARTED_DATE = DSL.field(
            DSL.name("eventlog", "action_events", "started_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
    private static final Field<Instant> COMPLETION_DATE = DSL.field(
            DSL.name("eventlog", "action_events", "completion_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
    private static final Field<String> ACTION_NAME =
            DSL.field(DSL.name("eventlog", "action_events", "action_name"), String.class);
    private static final Field<ObjectNode> ACTION_PARAMS = DSL.field(
            DSL.name("eventlog", "action_events", "action_params"),
            SQLDataType.JSONB.asConvertedDataType(new ObjectNodeConverter()));

    public ActionEventEntityRepository(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
        this.db = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), transactionManager.dialect);
    }

    private DSLContext txDbElseDb() {
        return transactionManager.currentTransactionDbContext().orElse(db);
    }

    public void addNoResult(ActionEventEntity entity) {
        txDbElseDb()
                .insertInto(TABLE)
                .set(ID, entity.id)
                .set(STARTED_DATE, entity.startedDate)
                .set(COMPLETION_DATE, entity.completionDate)
                .set(ACTION_NAME, entity.actionName)
                .set(ACTION_PARAMS, entity.actionParams)
                .execute();
    }
}
