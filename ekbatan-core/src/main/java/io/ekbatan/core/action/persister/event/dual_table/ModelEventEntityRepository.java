package io.ekbatan.core.action.persister.event.dual_table;

import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.persistence.jooq.converter.InstantConverter;
import io.ekbatan.core.persistence.jooq.converter.JSONBObjectNodeConverter;
import io.ekbatan.core.persistence.jooq.converter.JSONObjectNodeConverter;
import io.ekbatan.core.persistence.jooq.converter.mysql.UuidStringConverter;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import tools.jackson.databind.node.ObjectNode;

public class ModelEventEntityRepository {

    private final TransactionManager transactionManager;
    private final DSLContext db;
    private final Field<UUID> idField;
    private final Field<UUID> actionIdField;
    private final Field<String> modelIdField;
    private final Field<String> modelTypeField;
    private final Field<String> eventTypeField;
    private final Field<ObjectNode> payloadField;
    private final Field<Instant> eventDateField;

    private static final String SCHEMA_NAME = "eventlog";
    private static final String TABLE_NAME = "model_events";

    private static final org.jooq.Table<?> MODEL_EVENTS = DSL.table(DSL.name(SCHEMA_NAME, TABLE_NAME));

    // PG Definitions
    private static final Field<UUID> PG_ID = DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "id"), UUID.class);
    private static final Field<UUID> PG_ACTION_ID =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "action_id"), UUID.class);
    private static final Field<String> PG_MODEL_ID =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "model_id"), String.class);
    private static final Field<String> PG_MODEL_TYPE =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "model_type"), String.class);
    private static final Field<String> PG_EVENT_TYPE =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "event_type"), String.class);
    private static final Field<ObjectNode> PG_PAYLOAD = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "payload"),
            SQLDataType.JSONB.asConvertedDataType(new JSONBObjectNodeConverter()));
    private static final Field<Instant> PG_EVENT_DATE = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "event_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));

    // MariaDB Definitions
    private static final Field<UUID> MARIADB_ID = DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "id"), UUID.class);
    private static final Field<UUID> MARIADB_ACTION_ID =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "action_id"), UUID.class);
    private static final Field<String> MARIADB_MODEL_ID =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "model_id"), String.class);
    private static final Field<String> MARIADB_MODEL_TYPE =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "model_type"), String.class);
    private static final Field<String> MARIADB_EVENT_TYPE =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "event_type"), String.class);
    private static final Field<ObjectNode> MARIADB_PAYLOAD = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "payload"),
            SQLDataType.JSON.asConvertedDataType(new JSONObjectNodeConverter()));
    private static final Field<Instant> MARIADB_EVENT_DATE = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "event_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));

    // MySQL Definitions
    private static final Field<UUID> MYSQL_ID = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "id"),
            SQLDataType.CHAR(36).asConvertedDataType(new UuidStringConverter()));
    private static final Field<UUID> MYSQL_ACTION_ID = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "action_id"),
            SQLDataType.CHAR(36).asConvertedDataType(new UuidStringConverter()));
    private static final Field<String> MYSQL_MODEL_ID =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "model_id"), String.class);
    private static final Field<String> MYSQL_MODEL_TYPE =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "model_type"), String.class);
    private static final Field<String> MYSQL_EVENT_TYPE =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "event_type"), String.class);
    private static final Field<ObjectNode> MYSQL_PAYLOAD = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "payload"),
            SQLDataType.JSON.asConvertedDataType(new JSONObjectNodeConverter()));
    private static final Field<Instant> MYSQL_EVENT_DATE = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "event_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));

    public ModelEventEntityRepository(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
        this.db = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), transactionManager.dialect);

        if (transactionManager.dialect.family() == SQLDialect.MYSQL) {
            this.idField = MYSQL_ID;
            this.actionIdField = MYSQL_ACTION_ID;
            this.modelIdField = MYSQL_MODEL_ID;
            this.modelTypeField = MYSQL_MODEL_TYPE;
            this.eventTypeField = MYSQL_EVENT_TYPE;
            this.payloadField = MYSQL_PAYLOAD;
            this.eventDateField = MYSQL_EVENT_DATE;
        } else if (transactionManager.dialect.family() == SQLDialect.MARIADB) {
            this.idField = MARIADB_ID;
            this.actionIdField = MARIADB_ACTION_ID;
            this.modelIdField = MARIADB_MODEL_ID;
            this.modelTypeField = MARIADB_MODEL_TYPE;
            this.eventTypeField = MARIADB_EVENT_TYPE;
            this.payloadField = MARIADB_PAYLOAD;
            this.eventDateField = MARIADB_EVENT_DATE;
        } else {
            this.idField = PG_ID;
            this.actionIdField = PG_ACTION_ID;
            this.modelIdField = PG_MODEL_ID;
            this.modelTypeField = PG_MODEL_TYPE;
            this.eventTypeField = PG_EVENT_TYPE;
            this.payloadField = PG_PAYLOAD;
            this.eventDateField = PG_EVENT_DATE;
        }
    }

    private DSLContext txDbElseDb() {
        return transactionManager.currentTransactionDbContext().orElse(db);
    }

    public void addAllNoResult(Collection<ModelEventEntity> entities) {
        if (entities.isEmpty()) return;
        final var insert = txDbElseDb()
                .insertInto(
                        MODEL_EVENTS,
                        idField,
                        actionIdField,
                        modelIdField,
                        modelTypeField,
                        eventTypeField,
                        payloadField,
                        eventDateField);
        for (var entity : entities) {
            insert.values(
                    entity.id,
                    entity.actionId,
                    entity.modelId,
                    entity.modelType,
                    entity.eventType,
                    entity.payload,
                    entity.eventDate);
        }
        insert.execute();
    }
}
