package io.ekbatan.events.localeventhandler.repository;

import io.ekbatan.core.persistence.jooq.converter.InstantConverter;
import io.ekbatan.core.persistence.jooq.converter.JSONBObjectNodeConverter;
import io.ekbatan.core.persistence.jooq.converter.JSONObjectNodeConverter;
import io.ekbatan.core.persistence.jooq.converter.mysql.UuidStringConverter;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.events.localeventhandler.model.EventEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.Validate;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import tools.jackson.databind.node.ObjectNode;

/**
 * JOOQ-style repository for {@code eventlog.events}, mirroring the layout of
 * {@code SingleTableJsonEventPersister}'s package-private {@code EventEntityRepository} in
 * {@code ekbatan-core}. Field definitions for {@code UUID} and JSON columns are dialect-
 * specific (selected at construction time); everything else is dialect-neutral.
 *
 * <p>Reads route through {@link #readonlyDb(ShardIdentifier)} (replica). Writes route
 * through {@link #txDbElseDb(ShardIdentifier)} which prefers the active transaction's
 * connection when one is open on the same shard, falling back to primary otherwise. The
 * helper shape mirrors {@code AbstractRepository}'s {@code db / readonlyDb / txDb /
 * txDbElseDb} families.
 */
public final class EventEntityRepository {

    private static final String SCHEMA = "eventlog";
    private static final String TABLE_NAME = "events";

    private static final Table<?> EVENTS = DSL.table(DSL.name(SCHEMA, TABLE_NAME));

    // Dialect-neutral fields (String, Instant, Boolean) — same shape on PG/MariaDB/MySQL.
    private static final Field<String> NAMESPACE = DSL.field(DSL.name(SCHEMA, TABLE_NAME, "namespace"), String.class);
    private static final Field<String> ACTION_NAME =
            DSL.field(DSL.name(SCHEMA, TABLE_NAME, "action_name"), String.class);
    private static final Field<Instant> STARTED_DATE = DSL.field(
            DSL.name(SCHEMA, TABLE_NAME, "started_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
    private static final Field<Instant> COMPLETION_DATE = DSL.field(
            DSL.name(SCHEMA, TABLE_NAME, "completion_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
    private static final Field<String> MODEL_ID = DSL.field(DSL.name(SCHEMA, TABLE_NAME, "model_id"), String.class);
    private static final Field<String> MODEL_TYPE = DSL.field(DSL.name(SCHEMA, TABLE_NAME, "model_type"), String.class);
    private static final Field<String> EVENT_TYPE = DSL.field(DSL.name(SCHEMA, TABLE_NAME, "event_type"), String.class);
    private static final Field<Instant> EVENT_DATE = DSL.field(
            DSL.name(SCHEMA, TABLE_NAME, "event_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
    private static final Field<Boolean> DELIVERED = DSL.field(DSL.name(SCHEMA, TABLE_NAME, "delivered"), Boolean.class);

    // Dialect-specific fields. UUIDs are native on PG/MariaDB; CHAR(36)+converter on MySQL.
    // JSON columns are JSONB on PG; JSON on MariaDB/MySQL.
    private static final Field<UUID> PG_ID = DSL.field(DSL.name(SCHEMA, TABLE_NAME, "id"), UUID.class);
    private static final Field<UUID> PG_ACTION_ID = DSL.field(DSL.name(SCHEMA, TABLE_NAME, "action_id"), UUID.class);
    private static final Field<ObjectNode> PG_ACTION_PARAMS = DSL.field(
            DSL.name(SCHEMA, TABLE_NAME, "action_params"),
            SQLDataType.JSONB.asConvertedDataType(new JSONBObjectNodeConverter()));
    private static final Field<ObjectNode> PG_PAYLOAD = DSL.field(
            DSL.name(SCHEMA, TABLE_NAME, "payload"),
            SQLDataType.JSONB.asConvertedDataType(new JSONBObjectNodeConverter()));

    private static final Field<UUID> MARIADB_ID = DSL.field(DSL.name(SCHEMA, TABLE_NAME, "id"), UUID.class);
    private static final Field<UUID> MARIADB_ACTION_ID =
            DSL.field(DSL.name(SCHEMA, TABLE_NAME, "action_id"), UUID.class);
    private static final Field<ObjectNode> MARIADB_ACTION_PARAMS = DSL.field(
            DSL.name(SCHEMA, TABLE_NAME, "action_params"),
            SQLDataType.JSON.asConvertedDataType(new JSONObjectNodeConverter()));
    private static final Field<ObjectNode> MARIADB_PAYLOAD = DSL.field(
            DSL.name(SCHEMA, TABLE_NAME, "payload"),
            SQLDataType.JSON.asConvertedDataType(new JSONObjectNodeConverter()));

    private static final Field<UUID> MYSQL_ID = DSL.field(
            DSL.name(SCHEMA, TABLE_NAME, "id"), SQLDataType.CHAR(36).asConvertedDataType(new UuidStringConverter()));
    private static final Field<UUID> MYSQL_ACTION_ID = DSL.field(
            DSL.name(SCHEMA, TABLE_NAME, "action_id"),
            SQLDataType.CHAR(36).asConvertedDataType(new UuidStringConverter()));
    private static final Field<ObjectNode> MYSQL_ACTION_PARAMS = DSL.field(
            DSL.name(SCHEMA, TABLE_NAME, "action_params"),
            SQLDataType.JSON.asConvertedDataType(new JSONObjectNodeConverter()));
    private static final Field<ObjectNode> MYSQL_PAYLOAD = DSL.field(
            DSL.name(SCHEMA, TABLE_NAME, "payload"),
            SQLDataType.JSON.asConvertedDataType(new JSONObjectNodeConverter()));

    private final DatabaseRegistry databaseRegistry;

    private final Field<UUID> idField;
    private final Field<UUID> actionIdField;
    private final Field<ObjectNode> actionParamsField;
    private final Field<ObjectNode> payloadField;

    public EventEntityRepository(DatabaseRegistry databaseRegistry) {
        this.databaseRegistry = Validate.notNull(databaseRegistry, "databaseRegistry cannot be null");
        final var defaultTm = databaseRegistry.defaultTransactionManager();

        if (defaultTm.dialect.family() == SQLDialect.MYSQL) {
            this.idField = MYSQL_ID;
            this.actionIdField = MYSQL_ACTION_ID;
            this.actionParamsField = MYSQL_ACTION_PARAMS;
            this.payloadField = MYSQL_PAYLOAD;
        } else if (defaultTm.dialect.family() == SQLDialect.MARIADB) {
            this.idField = MARIADB_ID;
            this.actionIdField = MARIADB_ACTION_ID;
            this.actionParamsField = MARIADB_ACTION_PARAMS;
            this.payloadField = MARIADB_PAYLOAD;
        } else {
            this.idField = PG_ID;
            this.actionIdField = PG_ACTION_ID;
            this.actionParamsField = PG_ACTION_PARAMS;
            this.payloadField = PG_PAYLOAD;
        }
    }

    // --- db() variants — primary connection ---

    private DSLContext db() {
        return databaseRegistry.primary.get(databaseRegistry.defaultShard);
    }

    private DSLContext db(ShardIdentifier shard) {
        return databaseRegistry.primary.get(shard);
    }

    private Collection<DSLContext> dbs() {
        return databaseRegistry.primary.values();
    }

    // --- readonlyDb() variants — replica/secondary connection ---

    private DSLContext readonlyDb() {
        return databaseRegistry.secondary.get(databaseRegistry.defaultShard);
    }

    private DSLContext readonlyDb(ShardIdentifier shard) {
        return databaseRegistry.secondary.get(shard);
    }

    private Collection<DSLContext> readonlyDbs() {
        return databaseRegistry.secondary.values();
    }

    // --- txDb() variants — currently-open transaction's connection, if any ---

    private Optional<DSLContext> txDb() {
        return databaseRegistry.defaultTransactionManager().currentTransactionDbContext();
    }

    private Optional<DSLContext> txDb(ShardIdentifier shard) {
        return databaseRegistry.transactionManager(shard).currentTransactionDbContext();
    }

    // --- txDbElseDb() variants — transaction connection if open, primary otherwise ---

    private DSLContext txDbElseDb() {
        return txDb().orElseGet(this::db);
    }

    private DSLContext txDbElseDb(ShardIdentifier shard) {
        return txDb(shard).orElseGet(() -> db(shard));
    }

    /**
     * Insert one row per event, writing {@code delivered} explicitly so the inserted row
     * matches {@link EventEntity#delivered}. Newly-persisted events default to {@code false}
     * via {@link EventEntity.Builder}. Empty input is a no-op. Single round-trip via
     * multi-row INSERT.
     */
    public void addAllNoResult(Collection<EventEntity> entities, ShardIdentifier shard) {
        if (entities.isEmpty()) return;
        final var insert = txDbElseDb(shard)
                .insertInto(
                        EVENTS,
                        idField,
                        NAMESPACE,
                        actionIdField,
                        ACTION_NAME,
                        actionParamsField,
                        STARTED_DATE,
                        COMPLETION_DATE,
                        MODEL_ID,
                        MODEL_TYPE,
                        EVENT_TYPE,
                        payloadField,
                        EVENT_DATE,
                        DELIVERED);
        for (var e : entities) {
            insert.values(
                    e.id,
                    e.namespace,
                    e.actionId,
                    e.actionName,
                    e.actionParams,
                    e.startedDate,
                    e.completionDate,
                    e.modelId,
                    e.modelType,
                    e.eventType,
                    e.payload,
                    e.eventDate,
                    e.delivered);
        }
        insert.execute();
    }

    /**
     * Read up to {@code limit} undelivered, non-sentinel events ordered by {@code event_date}
     * ascending. Sentinel rows (zero-event actions, where {@code event_type} is NULL) are
     * filtered out — they have no handlers to fan out to.
     */
    public List<EventEntity> findUndelivered(ShardIdentifier shard, int limit) {
        return readonlyDb(shard)
                .select(
                        idField,
                        NAMESPACE,
                        actionIdField,
                        ACTION_NAME,
                        actionParamsField,
                        STARTED_DATE,
                        COMPLETION_DATE,
                        MODEL_ID,
                        MODEL_TYPE,
                        EVENT_TYPE,
                        payloadField,
                        EVENT_DATE,
                        DELIVERED)
                .from(EVENTS)
                .where(DELIVERED.eq(false))
                .and(EVENT_TYPE.isNotNull())
                .orderBy(EVENT_DATE)
                .limit(limit)
                .fetch()
                .map(r -> EventEntity.createEventEntity(
                                r.get(idField),
                                r.get(NAMESPACE),
                                r.get(actionIdField),
                                r.get(ACTION_NAME),
                                r.get(actionParamsField),
                                r.get(STARTED_DATE),
                                r.get(COMPLETION_DATE),
                                r.get(MODEL_ID),
                                r.get(MODEL_TYPE),
                                r.get(EVENT_TYPE),
                                r.get(payloadField),
                                r.get(EVENT_DATE),
                                r.get(DELIVERED))
                        .build());
    }

    /** Flip {@code delivered = TRUE} for the given event ids. Empty input is a no-op. */
    public void markDelivered(Collection<UUID> ids, ShardIdentifier shard) {
        if (ids.isEmpty()) return;
        txDbElseDb(shard)
                .update(EVENTS)
                .set(DELIVERED, true)
                .where(idField.in(ids))
                .execute();
    }
}
