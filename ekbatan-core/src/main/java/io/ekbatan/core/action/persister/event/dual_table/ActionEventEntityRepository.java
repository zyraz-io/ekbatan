package io.ekbatan.core.action.persister.event.dual_table;

import io.ekbatan.core.persistence.jooq.converter.InstantConverter;
import io.ekbatan.core.persistence.jooq.converter.JSONBObjectNodeConverter;
import io.ekbatan.core.persistence.jooq.converter.JSONObjectNodeConverter;
import io.ekbatan.core.persistence.jooq.converter.mysql.UuidStringConverter;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import tools.jackson.databind.node.ObjectNode;

class ActionEventEntityRepository {

    private final DatabaseRegistry databaseRegistry;
    private final DSLContext db;
    private final Field<UUID> idField;
    private final Field<Instant> startedDateField;
    private final Field<Instant> completionDateField;
    private final Field<String> actionNameField;
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

    ActionEventEntityRepository(DatabaseRegistry databaseRegistry) {
        this.databaseRegistry = databaseRegistry;
        var defaultTransactionManager = databaseRegistry.defaultTransactionManager();
        this.db = DSL.using(
                defaultTransactionManager.primaryConnectionProvider.getDataSource(), defaultTransactionManager.dialect);

        if (defaultTransactionManager.dialect.family() == SQLDialect.MYSQL) {
            this.idField = MYSQL_ID;
            this.startedDateField = MYSQL_STARTED_DATE;
            this.completionDateField = MYSQL_COMPLETION_DATE;
            this.actionNameField = MYSQL_ACTION_NAME;
            this.actionParamsField = MYSQL_ACTION_PARAMS;
        } else if (defaultTransactionManager.dialect.family() == SQLDialect.MARIADB) {
            this.idField = MARIADB_ID;
            this.startedDateField = MARIADB_STARTED_DATE;
            this.completionDateField = MARIADB_COMPLETION_DATE;
            this.actionNameField = MARIADB_ACTION_NAME;
            this.actionParamsField = MARIADB_ACTION_PARAMS;
        } else {
            this.idField = PG_ID;
            this.startedDateField = PG_STARTED_DATE;
            this.completionDateField = PG_COMPLETION_DATE;
            this.actionNameField = PG_ACTION_NAME;
            this.actionParamsField = PG_ACTION_PARAMS;
        }
    }

    private DSLContext txDbElseDb(ShardIdentifier shard) {
        return databaseRegistry
                .transactionManager(shard)
                .currentTransactionDbContext()
                .orElse(db);
    }

    int count(ShardIdentifier shard) {
        return txDbElseDb(shard).selectCount().from(ACTION_EVENTS).fetchOne(0, int.class);
    }

    List<ActionEventEntity> findAll(ShardIdentifier shard) {
        return txDbElseDb(shard)
                .select(idField, startedDateField, completionDateField, actionNameField, actionParamsField)
                .from(ACTION_EVENTS)
                .fetch()
                .map(r -> ActionEventEntity.createActionEventEntity(
                                r.get(idField),
                                r.get(startedDateField),
                                r.get(completionDateField),
                                r.get(actionNameField),
                                r.get(actionParamsField))
                        .build());
    }

    void addNoResult(ActionEventEntity entity, ShardIdentifier shard) {
        txDbElseDb(shard)
                .insertInto(ACTION_EVENTS)
                .set(idField, entity.id)
                .set(startedDateField, entity.startedDate)
                .set(completionDateField, entity.completionDate)
                .set(actionNameField, entity.actionName)
                .set(actionParamsField, entity.actionParams)
                .execute();
    }
}
