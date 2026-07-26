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
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import tools.jackson.databind.node.ObjectNode;

/**
 * jOOQ-backed writer for the default {@code eventlog.events} table used by
 * {@link SingleTableJsonEventPersister}.
 *
 * <p>Dialect-specific {@code UUID} and JSON column definitions are resolved <em>per target
 * shard</em> via {@link #fieldsFor(ShardIdentifier)}, not once at construction time, so a registry
 * whose shards run different dialects binds the right converters for each. Dialect-neutral columns
 * ({@code String}, {@code Instant}, {@code Boolean}) are shared constants. This matches
 * {@code ekbatan-events:local-event-handler}'s repositories - see AGENTS.md, "Repository
 * field-definition pattern".
 */
class EventEntityRepository {

    private static final String SCHEMA_NAME = "eventlog";
    private static final String TABLE_NAME = "events";

    private static final Table<?> EVENTS = DSL.table(DSL.name(SCHEMA_NAME, TABLE_NAME));

    // Dialect-neutral fields (String, Instant, Boolean) - identical on PG/MariaDB/MySQL.
    private static final Field<String> NAMESPACE =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "namespace"), String.class);
    private static final Field<String> ACTION_NAME =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "action_name"), String.class);
    private static final Field<Instant> STARTED_DATE = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "started_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
    private static final Field<Instant> COMPLETION_DATE = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "completion_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
    private static final Field<String> MODEL_ID =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "model_id"), String.class);
    private static final Field<String> MODEL_TYPE =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "model_type"), String.class);
    private static final Field<String> EVENT_TYPE =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "event_type"), String.class);
    private static final Field<Instant> EVENT_DATE = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "event_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
    private static final Field<Boolean> DELIVERED =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "delivered"), Boolean.class);

    // Dialect-specific fields. UUIDs are native on PG/MariaDB; CHAR(36)+converter on MySQL.
    // JSON columns are JSONB on PG; JSON on MariaDB/MySQL.
    private static final Field<UUID> PG_ID = DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "id"), UUID.class);
    private static final Field<UUID> PG_ACTION_ID =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "action_id"), UUID.class);
    private static final Field<ObjectNode> PG_ACTION_PARAMS = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "action_params"),
            SQLDataType.JSONB.asConvertedDataType(new JSONBObjectNodeConverter()));
    private static final Field<ObjectNode> PG_PAYLOAD = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "payload"),
            SQLDataType.JSONB.asConvertedDataType(new JSONBObjectNodeConverter()));

    private static final Field<UUID> MARIADB_ID = DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "id"), UUID.class);
    private static final Field<UUID> MARIADB_ACTION_ID =
            DSL.field(DSL.name(SCHEMA_NAME, TABLE_NAME, "action_id"), UUID.class);
    private static final Field<ObjectNode> MARIADB_ACTION_PARAMS = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "action_params"),
            SQLDataType.JSON.asConvertedDataType(new JSONObjectNodeConverter()));
    private static final Field<ObjectNode> MARIADB_PAYLOAD = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "payload"),
            SQLDataType.JSON.asConvertedDataType(new JSONObjectNodeConverter()));

    private static final Field<UUID> MYSQL_ID = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "id"),
            SQLDataType.CHAR(36).asConvertedDataType(new UuidStringConverter()));
    private static final Field<UUID> MYSQL_ACTION_ID = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "action_id"),
            SQLDataType.CHAR(36).asConvertedDataType(new UuidStringConverter()));
    private static final Field<ObjectNode> MYSQL_ACTION_PARAMS = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "action_params"),
            SQLDataType.JSON.asConvertedDataType(new JSONObjectNodeConverter()));
    private static final Field<ObjectNode> MYSQL_PAYLOAD = DSL.field(
            DSL.name(SCHEMA_NAME, TABLE_NAME, "payload"),
            SQLDataType.JSON.asConvertedDataType(new JSONObjectNodeConverter()));

    private final DatabaseRegistry databaseRegistry;

    EventEntityRepository(DatabaseRegistry databaseRegistry) {
        this.databaseRegistry = databaseRegistry;
    }

    private DSLContext db(ShardIdentifier shard) {
        return databaseRegistry.primary.get(shard);
    }

    /**
     * Event rows are always written inside the executor's per-shard transaction, so this normally
     * returns that transaction's context. The fallback is the requested shard's primary - never the
     * default shard's - so out-of-transaction reads still hit the shard they asked for.
     */
    private DSLContext txDbElseDb(ShardIdentifier shard) {
        return databaseRegistry
                .transactionManager(shard)
                .currentTransactionDbContext()
                .orElseGet(() -> db(shard));
    }

    int count(ShardIdentifier shard) {
        return txDbElseDb(shard).selectCount().from(EVENTS).fetchOne(0, int.class);
    }

    List<EventEntity> findAll(ShardIdentifier shard) {
        final var fields = fieldsFor(shard);
        return txDbElseDb(shard)
                .select(
                        fields.id(),
                        NAMESPACE,
                        fields.actionId(),
                        ACTION_NAME,
                        fields.actionParams(),
                        STARTED_DATE,
                        COMPLETION_DATE,
                        MODEL_ID,
                        MODEL_TYPE,
                        EVENT_TYPE,
                        fields.payload(),
                        EVENT_DATE,
                        DELIVERED)
                .from(EVENTS)
                .fetch()
                .map(r -> toEntity(r, fields));
    }

    List<EventEntity> findByActionId(UUID actionId, ShardIdentifier shard) {
        final var fields = fieldsFor(shard);
        return txDbElseDb(shard)
                .select(
                        fields.id(),
                        NAMESPACE,
                        fields.actionId(),
                        ACTION_NAME,
                        fields.actionParams(),
                        STARTED_DATE,
                        COMPLETION_DATE,
                        MODEL_ID,
                        MODEL_TYPE,
                        EVENT_TYPE,
                        fields.payload(),
                        EVENT_DATE,
                        DELIVERED)
                .from(EVENTS)
                .where(fields.actionId().eq(actionId))
                .fetch()
                .map(r -> toEntity(r, fields));
    }

    void addAllNoResult(Collection<EventEntity> entities, ShardIdentifier shard) {
        if (entities.isEmpty()) return;
        final var fields = fieldsFor(shard);
        final var insert = txDbElseDb(shard)
                .insertInto(
                        EVENTS,
                        fields.id(),
                        NAMESPACE,
                        fields.actionId(),
                        ACTION_NAME,
                        fields.actionParams(),
                        STARTED_DATE,
                        COMPLETION_DATE,
                        MODEL_ID,
                        MODEL_TYPE,
                        EVENT_TYPE,
                        fields.payload(),
                        EVENT_DATE,
                        DELIVERED);
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
                    entity.eventDate,
                    entity.delivered);
        }
        insert.execute();
    }

    private static EventEntity toEntity(Record r, DialectEventFields fields) {
        return EventEntity.createEventEntity(
                        r.get(fields.id()),
                        r.get(NAMESPACE),
                        r.get(fields.actionId()),
                        r.get(ACTION_NAME),
                        r.get(fields.actionParams()),
                        r.get(STARTED_DATE),
                        r.get(COMPLETION_DATE),
                        r.get(MODEL_ID),
                        r.get(MODEL_TYPE),
                        r.get(EVENT_TYPE),
                        r.get(fields.payload()),
                        r.get(EVENT_DATE))
                .delivered(r.get(DELIVERED))
                .build();
    }

    private DialectEventFields fieldsFor(ShardIdentifier shard) {
        final var dialect = databaseRegistry.transactionManager(shard).dialect.family();
        if (dialect == SQLDialect.MYSQL) {
            return new DialectEventFields(MYSQL_ID, MYSQL_ACTION_ID, MYSQL_ACTION_PARAMS, MYSQL_PAYLOAD);
        }
        if (dialect == SQLDialect.MARIADB) {
            return new DialectEventFields(MARIADB_ID, MARIADB_ACTION_ID, MARIADB_ACTION_PARAMS, MARIADB_PAYLOAD);
        }
        return new DialectEventFields(PG_ID, PG_ACTION_ID, PG_ACTION_PARAMS, PG_PAYLOAD);
    }

    private record DialectEventFields(
            Field<UUID> id, Field<UUID> actionId, Field<ObjectNode> actionParams, Field<ObjectNode> payload) {}
}
