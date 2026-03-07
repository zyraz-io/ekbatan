package io.ekbatan.core.action.persister.event.single_table;

import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.persistence.jooq.converter.InstantConverter;
import io.ekbatan.core.persistence.jooq.converter.JSONArrayNodeConverter;
import io.ekbatan.core.persistence.jooq.converter.JSONBArrayNodeConverter;
import io.ekbatan.core.persistence.jooq.converter.JSONBObjectNodeConverter;
import io.ekbatan.core.persistence.jooq.converter.JSONObjectNodeConverter;
import io.ekbatan.core.persistence.jooq.converter.mysql.UuidStringConverter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.Validate;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ActionEventEntityRepository {

    private final ObjectMapper objectMapper;
    private final TransactionManager transactionManager;
    private final DSLContext db;
    private final Field<UUID> idField;
    private final Field<Instant> startedDateField;
    private final Field<Instant> completionDateField;
    private final Field<String> actionNameField;
    private final Field<ArrayNode> modelEventsField;
    private final Field<ObjectNode> actionParamsField;

    private static final String SCHEMA_NAME = "eventlog";
    private static final String TABLE_NAME = "action_events";

    private static final org.jooq.Table<?> ACTION_EVENTS = DSL.table(DSL.name(SCHEMA_NAME, TABLE_NAME));

    // PG Definitions
    private static final Field<UUID> PG_ID = DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "id"), UUID.class);
    private static final Field<Instant> PG_STARTED_DATE = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "started_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
    private static final Field<Instant> PG_COMPLETION_DATE = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "completion_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
    private static final Field<String> PG_ACTION_NAME =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "action_name"), String.class);
    private static final Field<ObjectNode> PG_ACTION_PARAMS = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "action_params"),
            SQLDataType.JSONB.asConvertedDataType(new JSONBObjectNodeConverter()));
    private static final Field<ArrayNode> PG_MODEL_EVENTS = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "model_events"),
            SQLDataType.JSONB.asConvertedDataType(new JSONBArrayNodeConverter()));

    // MariaDB Definitions
    private static final Field<UUID> MARIADB_ID = DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "id"), UUID.class);
    private static final Field<Instant> MARIADB_STARTED_DATE = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "started_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
    private static final Field<Instant> MARIADB_COMPLETION_DATE = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "completion_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
    private static final Field<String> MARIADB_ACTION_NAME =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "action_name"), String.class);
    private static final Field<ObjectNode> MARIADB_ACTION_PARAMS = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "action_params"),
            SQLDataType.JSON.asConvertedDataType(new JSONObjectNodeConverter()));
    private static final Field<ArrayNode> MARIADB_MODEL_EVENTS = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "model_events"),
            SQLDataType.JSON.asConvertedDataType(new JSONArrayNodeConverter()));

    // MySQL Definitions
    private static final Field<UUID> MYSQL_ID = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "id"),
            SQLDataType.CHAR(36).asConvertedDataType(new UuidStringConverter()));
    private static final Field<Instant> MYSQL_STARTED_DATE = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "started_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
    private static final Field<Instant> MYSQL_COMPLETION_DATE = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "completion_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
    private static final Field<String> MYSQL_ACTION_NAME =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "action_name"), String.class);
    private static final Field<ObjectNode> MYSQL_ACTION_PARAMS = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "action_params"),
            SQLDataType.JSON.asConvertedDataType(new JSONObjectNodeConverter()));
    private static final Field<ArrayNode> MYSQL_MODEL_EVENTS = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "model_events"),
            SQLDataType.JSON.asConvertedDataType(new JSONArrayNodeConverter()));

    ActionEventEntityRepository(TransactionManager transactionManager, ObjectMapper objectMapper) {
        this.transactionManager = Validate.notNull(transactionManager, "transactionManager cannot be null");
        this.objectMapper = Validate.notNull(objectMapper, "objectMapper cannot be null");
        this.db = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), transactionManager.dialect);

        if (transactionManager.dialect.family() == SQLDialect.MYSQL) {
            this.idField = MYSQL_ID;
            this.startedDateField = MYSQL_STARTED_DATE;
            this.completionDateField = MYSQL_COMPLETION_DATE;
            this.actionNameField = MYSQL_ACTION_NAME;
            this.actionParamsField = MYSQL_ACTION_PARAMS;
            this.modelEventsField = MYSQL_MODEL_EVENTS;
        } else if (transactionManager.dialect.family() == SQLDialect.MARIADB) {
            this.idField = MARIADB_ID;
            this.startedDateField = MARIADB_STARTED_DATE;
            this.completionDateField = MARIADB_COMPLETION_DATE;
            this.actionNameField = MARIADB_ACTION_NAME;
            this.actionParamsField = MARIADB_ACTION_PARAMS;
            this.modelEventsField = MARIADB_MODEL_EVENTS;
        } else {
            this.idField = PG_ID;
            this.startedDateField = PG_STARTED_DATE;
            this.completionDateField = PG_COMPLETION_DATE;
            this.actionNameField = PG_ACTION_NAME;
            this.actionParamsField = PG_ACTION_PARAMS;
            this.modelEventsField = PG_MODEL_EVENTS;
        }
    }

    private DSLContext txDbElseDb() {
        return transactionManager.currentTransactionDbContext().orElse(db);
    }

    int count() {
        return txDbElseDb().selectCount().from(ACTION_EVENTS).fetchOne(0, int.class);
    }

    List<ActionEventEntity> findAll() {
        return txDbElseDb()
                .select(
                        idField,
                        startedDateField,
                        completionDateField,
                        actionNameField,
                        actionParamsField,
                        modelEventsField)
                .from(ACTION_EVENTS)
                .fetch()
                .map(r -> {
                    List<ModelEventEmbedded> modelEventsList = objectMapper.convertValue(
                            r.get(modelEventsField), new TypeReference<List<ModelEventEmbedded>>() {});
                    return ActionEventEntity.createActionEventEntity(
                                    r.get(idField),
                                    r.get(startedDateField),
                                    r.get(completionDateField),
                                    r.get(actionNameField),
                                    modelEventsList,
                                    r.get(actionParamsField))
                            .build();
                });
    }

    void addNoResult(ActionEventEntity entity) {
        txDbElseDb()
                .insertInto(ACTION_EVENTS)
                .set(idField, entity.id)
                .set(startedDateField, entity.startedDate)
                .set(completionDateField, entity.completionDate)
                .set(actionNameField, entity.actionName)
                .set(actionParamsField, entity.actionParams)
                .set(modelEventsField, (ArrayNode) objectMapper.valueToTree(entity.modelEvents))
                .execute();
    }
}
