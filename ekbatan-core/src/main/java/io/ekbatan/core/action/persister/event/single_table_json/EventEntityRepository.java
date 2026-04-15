package io.ekbatan.core.action.persister.event.single_table_json;

import io.ekbatan.core.persistence.jooq.converter.InstantConverter;
import io.ekbatan.core.persistence.jooq.converter.JSONBObjectNodeConverter;
import io.ekbatan.core.persistence.jooq.converter.JSONObjectNodeConverter;
import io.ekbatan.core.persistence.jooq.converter.mysql.UuidStringConverter;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import tools.jackson.databind.node.ObjectNode;

class EventEntityRepository {

    private final DatabaseRegistry databaseRegistry;
    private final DSLContext db;
    private final Field<UUID> idField;
    private final Field<String> namespaceField;
    private final Field<UUID> actionIdField;
    private final Field<String> actionNameField;
    private final Field<ObjectNode> actionParamsField;
    private final Field<Instant> startedDateField;
    private final Field<Instant> completionDateField;
    private final Field<String> modelIdField;
    private final Field<String> modelTypeField;
    private final Field<String> eventTypeField;
    private final Field<ObjectNode> payloadField;
    private final Field<Instant> eventDateField;

    private static final String SCHEMA_NAME = "eventlog";
    private static final String TABLE_NAME = "events";

    private static final org.jooq.Table<?> EVENTS = DSL.table(DSL.name(SCHEMA_NAME, TABLE_NAME));

    // PG Definitions
    private static final Field<UUID> PG_ID = DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "id"), UUID.class);
    private static final Field<String> PG_NAMESPACE =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "namespace"), String.class);
    private static final Field<UUID> PG_ACTION_ID =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "action_id"), UUID.class);
    private static final Field<String> PG_ACTION_NAME =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "action_name"), String.class);
    private static final Field<ObjectNode> PG_ACTION_PARAMS = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "action_params"),
            SQLDataType.JSONB.asConvertedDataType(new JSONBObjectNodeConverter()));
    private static final Field<Instant> PG_STARTED_DATE = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "started_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
    private static final Field<Instant> PG_COMPLETION_DATE = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "completion_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
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
    private static final Field<String> MARIADB_NAMESPACE =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "namespace"), String.class);
    private static final Field<UUID> MARIADB_ACTION_ID =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "action_id"), UUID.class);
    private static final Field<String> MARIADB_ACTION_NAME =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "action_name"), String.class);
    private static final Field<ObjectNode> MARIADB_ACTION_PARAMS = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "action_params"),
            SQLDataType.JSON.asConvertedDataType(new JSONObjectNodeConverter()));
    private static final Field<Instant> MARIADB_STARTED_DATE = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "started_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
    private static final Field<Instant> MARIADB_COMPLETION_DATE = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "completion_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
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
    private static final Field<String> MYSQL_NAMESPACE =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "namespace"), String.class);
    private static final Field<UUID> MYSQL_ACTION_ID = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "action_id"),
            SQLDataType.CHAR(36).asConvertedDataType(new UuidStringConverter()));
    private static final Field<String> MYSQL_ACTION_NAME =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "action_name"), String.class);
    private static final Field<ObjectNode> MYSQL_ACTION_PARAMS = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "action_params"),
            SQLDataType.JSON.asConvertedDataType(new JSONObjectNodeConverter()));
    private static final Field<Instant> MYSQL_STARTED_DATE = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "started_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
    private static final Field<Instant> MYSQL_COMPLETION_DATE = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "completion_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
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

    EventEntityRepository(DatabaseRegistry databaseRegistry) {
        this.databaseRegistry = databaseRegistry;
        var defaultTransactionManager = databaseRegistry.defaultTransactionManager();
        this.db = DSL.using(
                defaultTransactionManager.primaryConnectionProvider.getDataSource(), defaultTransactionManager.dialect);

        if (defaultTransactionManager.dialect.family() == SQLDialect.MYSQL) {
            this.idField = MYSQL_ID;
            this.namespaceField = MYSQL_NAMESPACE;
            this.actionIdField = MYSQL_ACTION_ID;
            this.actionNameField = MYSQL_ACTION_NAME;
            this.actionParamsField = MYSQL_ACTION_PARAMS;
            this.startedDateField = MYSQL_STARTED_DATE;
            this.completionDateField = MYSQL_COMPLETION_DATE;
            this.modelIdField = MYSQL_MODEL_ID;
            this.modelTypeField = MYSQL_MODEL_TYPE;
            this.eventTypeField = MYSQL_EVENT_TYPE;
            this.payloadField = MYSQL_PAYLOAD;
            this.eventDateField = MYSQL_EVENT_DATE;
        } else if (defaultTransactionManager.dialect.family() == SQLDialect.MARIADB) {
            this.idField = MARIADB_ID;
            this.namespaceField = MARIADB_NAMESPACE;
            this.actionIdField = MARIADB_ACTION_ID;
            this.actionNameField = MARIADB_ACTION_NAME;
            this.actionParamsField = MARIADB_ACTION_PARAMS;
            this.startedDateField = MARIADB_STARTED_DATE;
            this.completionDateField = MARIADB_COMPLETION_DATE;
            this.modelIdField = MARIADB_MODEL_ID;
            this.modelTypeField = MARIADB_MODEL_TYPE;
            this.eventTypeField = MARIADB_EVENT_TYPE;
            this.payloadField = MARIADB_PAYLOAD;
            this.eventDateField = MARIADB_EVENT_DATE;
        } else {
            this.idField = PG_ID;
            this.namespaceField = PG_NAMESPACE;
            this.actionIdField = PG_ACTION_ID;
            this.actionNameField = PG_ACTION_NAME;
            this.actionParamsField = PG_ACTION_PARAMS;
            this.startedDateField = PG_STARTED_DATE;
            this.completionDateField = PG_COMPLETION_DATE;
            this.modelIdField = PG_MODEL_ID;
            this.modelTypeField = PG_MODEL_TYPE;
            this.eventTypeField = PG_EVENT_TYPE;
            this.payloadField = PG_PAYLOAD;
            this.eventDateField = PG_EVENT_DATE;
        }
    }

    private DSLContext txDbElseDb(ShardIdentifier shard) {
        return databaseRegistry
                .transactionManager(shard)
                .currentTransactionDbContext()
                .orElse(db);
    }

    int count(ShardIdentifier shard) {
        return txDbElseDb(shard).selectCount().from(EVENTS).fetchOne(0, int.class);
    }

    List<EventEntity> findAll(ShardIdentifier shard) {
        return txDbElseDb(shard)
                .select(
                        idField,
                        namespaceField,
                        actionIdField,
                        actionNameField,
                        actionParamsField,
                        startedDateField,
                        completionDateField,
                        modelIdField,
                        modelTypeField,
                        eventTypeField,
                        payloadField,
                        eventDateField)
                .from(EVENTS)
                .fetch()
                .map(r -> EventEntity.createEventEntity(
                                r.get(idField),
                                r.get(namespaceField),
                                r.get(actionIdField),
                                r.get(actionNameField),
                                r.get(actionParamsField),
                                r.get(startedDateField),
                                r.get(completionDateField),
                                r.get(modelIdField),
                                r.get(modelTypeField),
                                r.get(eventTypeField),
                                r.get(payloadField),
                                r.get(eventDateField))
                        .build());
    }

    List<EventEntity> findByActionId(UUID actionId, ShardIdentifier shard) {
        return txDbElseDb(shard)
                .select(
                        idField,
                        namespaceField,
                        actionIdField,
                        actionNameField,
                        actionParamsField,
                        startedDateField,
                        completionDateField,
                        modelIdField,
                        modelTypeField,
                        eventTypeField,
                        payloadField,
                        eventDateField)
                .from(EVENTS)
                .where(actionIdField.eq(actionId))
                .fetch()
                .map(r -> EventEntity.createEventEntity(
                                r.get(idField),
                                r.get(namespaceField),
                                r.get(actionIdField),
                                r.get(actionNameField),
                                r.get(actionParamsField),
                                r.get(startedDateField),
                                r.get(completionDateField),
                                r.get(modelIdField),
                                r.get(modelTypeField),
                                r.get(eventTypeField),
                                r.get(payloadField),
                                r.get(eventDateField))
                        .build());
    }

    void addAllNoResult(Collection<EventEntity> entities, ShardIdentifier shard) {
        if (entities.isEmpty()) return;
        final var insert = txDbElseDb(shard)
                .insertInto(
                        EVENTS,
                        idField,
                        namespaceField,
                        actionIdField,
                        actionNameField,
                        actionParamsField,
                        startedDateField,
                        completionDateField,
                        modelIdField,
                        modelTypeField,
                        eventTypeField,
                        payloadField,
                        eventDateField);
        for (var entity : entities) {
            insert.values(
                    entity.id,
                    entity.namespace,
                    entity.actionId,
                    entity.actionName,
                    entity.actionParams,
                    entity.startedDate,
                    entity.completionDate,
                    entity.modelId,
                    entity.modelType,
                    entity.eventType,
                    entity.payload,
                    entity.eventDate);
        }
        insert.execute();
    }
}
