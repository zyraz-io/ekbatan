package io.ekbatan.events.localeventhandler.repository;

import io.ekbatan.core.action.persister.event.single_table_json.EventEntity;
import io.ekbatan.core.persistence.jooq.converter.InstantConverter;
import io.ekbatan.core.persistence.jooq.converter.JSONBObjectNodeConverter;
import io.ekbatan.core.persistence.jooq.converter.JSONObjectNodeConverter;
import io.ekbatan.core.persistence.jooq.converter.mysql.UuidStringConverter;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
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
 * JOOQ-style repository for the in-process consumer's read path against
 * {@code eventlog.events}. Returns {@link EventEntity} (defined in {@code ekbatan-core});
 * writes go through {@code SingleTableJsonEventPersister} in core, not through this class.
 *
 * <p>The two queries here ({@link #findUndelivered(ShardIdentifier, Collection, int)}
 * and {@link #markDelivered}) are specific to the fan-out job and have no place in core.
 * Same field-constant convention as core's repository: dialect-specific {@code UUID} and
 * JSON column definitions resolved per shard, dialect-neutral fields shared as constants.
 */
public final class EventEntityRepository {

    private static final String SCHEMA = "eventlog";
    private static final String TABLE_NAME = "events";

    private static final Table<?> EVENTS = DSL.table(DSL.name(SCHEMA, TABLE_NAME));

    // Dialect-neutral fields (String, Instant, Boolean) - same shape on PG/MariaDB/MySQL.
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

    /**
     * Constructs the repository against a database registry. Dialect-specific jOOQ field
     * descriptors are resolved per target shard, so mixed-dialect registries do not reuse
     * the default shard's UUID/JSON column definitions.
     *
     * @param databaseRegistry the registry of per-shard connection pools / transaction managers.
     */
    public EventEntityRepository(DatabaseRegistry databaseRegistry) {
        this.databaseRegistry = Validate.notNull(databaseRegistry, "databaseRegistry cannot be null");
    }

    // --- db() variants - primary connection ---

    private DSLContext db() {
        return databaseRegistry.primary.get(databaseRegistry.defaultShard);
    }

    private DSLContext db(ShardIdentifier shard) {
        return databaseRegistry.primary.get(shard);
    }

    private Collection<DSLContext> dbs() {
        return databaseRegistry.primary.values();
    }

    // --- readonlyDb() variants - replica/secondary connection ---

    private DSLContext readonlyDb() {
        return databaseRegistry.secondary.get(databaseRegistry.defaultShard);
    }

    private DSLContext readonlyDb(ShardIdentifier shard) {
        return databaseRegistry.secondary.get(shard);
    }

    private Collection<DSLContext> readonlyDbs() {
        return databaseRegistry.secondary.values();
    }

    // --- txDb() variants - currently-open transaction's connection, if any ---

    private Optional<DSLContext> txDb() {
        return databaseRegistry.defaultTransactionManager().currentTransactionDbContext();
    }

    private Optional<DSLContext> txDb(ShardIdentifier shard) {
        return databaseRegistry.transactionManager(shard).currentTransactionDbContext();
    }

    // --- txDbElseDb() variants - transaction connection if open, primary otherwise ---

    private DSLContext txDbElseDb() {
        return txDb().orElseGet(this::db);
    }

    private DSLContext txDbElseDb(ShardIdentifier shard) {
        return txDb(shard).orElseGet(() -> db(shard));
    }

    /**
     * Read up to {@code limit} undelivered events whose {@code event_type} has at least one
     * currently registered handler, ordered by {@code event_date} ascending. Sentinel rows
     * (zero-event actions, where {@code event_type} is NULL) are filtered out because NULL is
     * never in {@code handledEventTypes}.
     *
     * @param shard             the shard to read from.
     * @param handledEventTypes event type simple names that currently have subscribers.
     * @param limit             max rows to return.
     * @return the actionable undelivered events on that shard, in chronological order.
     */
    public List<EventEntity> findUndelivered(ShardIdentifier shard, Collection<String> handledEventTypes, int limit) {
        Validate.notNull(handledEventTypes, "handledEventTypes cannot be null");
        if (handledEventTypes.isEmpty()) return List.of();
        final var fields = fieldsFor(shard);
        return readonlyDb(shard)
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
                .where(DELIVERED.eq(false))
                .and(EVENT_TYPE.in(handledEventTypes))
                .orderBy(EVENT_DATE)
                .limit(limit)
                .fetch()
                .map(r -> EventEntity.createEventEntity(
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
                        .build());
    }

    /**
     * Flip {@code delivered = TRUE} for the given event ids. Empty input is a no-op.
     *
     * @param ids   the event row ids to mark.
     * @param shard the shard the rows live on.
     */
    public void markDelivered(Collection<UUID> ids, ShardIdentifier shard) {
        if (ids.isEmpty()) return;
        final var fields = fieldsFor(shard);
        txDbElseDb(shard)
                .update(EVENTS)
                .set(DELIVERED, true)
                .where(fields.id().in(ids))
                .execute();
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
