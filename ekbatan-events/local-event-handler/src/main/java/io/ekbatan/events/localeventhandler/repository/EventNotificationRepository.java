package io.ekbatan.events.localeventhandler.repository;

import io.ekbatan.core.persistence.jooq.converter.InstantConverter;
import io.ekbatan.core.persistence.jooq.converter.JSONBObjectNodeConverter;
import io.ekbatan.core.persistence.jooq.converter.JSONObjectNodeConverter;
import io.ekbatan.core.persistence.jooq.converter.mysql.UuidStringConverter;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.events.localeventhandler.model.EventNotification;
import io.ekbatan.events.localeventhandler.model.EventNotificationState;
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
 * JOOQ-style repository for {@code eventlog.event_notifications}. Same layout convention
 * as {@link EventEntityRepository}: dialect-specific {@code UUID} and JSON column
 * definitions chosen at construction time, dialect-neutral fields shared as constants.
 *
 * <p>Notification rows carry a denormalized copy of the event and action context — see
 * {@link EventNotification}'s javadoc — so dispatch never has to JOIN against
 * {@code eventlog.events}.
 *
 * <p>Reads route through {@link #readonlyDb(ShardIdentifier)} (replica). Writes route
 * through {@link #txDbElseDb(ShardIdentifier)} which prefers the active transaction's
 * connection when one is open on the same shard, falling back to primary otherwise. The
 * helper shape mirrors {@code AbstractRepository}'s {@code db / readonlyDb / txDb /
 * txDbElseDb} families.
 */
public final class EventNotificationRepository {

    private static final String SCHEMA = "eventlog";
    private static final String TABLE_NAME = "event_notifications";

    private static final Table<?> NOTIFS = DSL.table(DSL.name(SCHEMA, TABLE_NAME));

    // Dialect-neutral fields.
    private static final Field<String> HANDLER_NAME =
            DSL.field(DSL.name(SCHEMA, TABLE_NAME, "handler_name"), String.class);
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
    private static final Field<String> STATE = DSL.field(DSL.name(SCHEMA, TABLE_NAME, "state"), String.class);
    private static final Field<Integer> ATTEMPTS = DSL.field(DSL.name(SCHEMA, TABLE_NAME, "attempts"), Integer.class);
    private static final Field<Instant> NEXT_RETRY_AT = DSL.field(
            DSL.name(SCHEMA, TABLE_NAME, "next_retry_at"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
    private static final Field<Instant> CREATED_DATE = DSL.field(
            DSL.name(SCHEMA, TABLE_NAME, "created_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));
    private static final Field<Instant> UPDATED_DATE = DSL.field(
            DSL.name(SCHEMA, TABLE_NAME, "updated_date"),
            SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter()));

    // Dialect-specific fields. UUIDs are native on PG/MariaDB; CHAR(36)+converter on MySQL.
    // JSON columns are JSONB on PG; JSON on MariaDB/MySQL.
    private static final Field<UUID> PG_ID = DSL.field(DSL.name(SCHEMA, TABLE_NAME, "id"), UUID.class);
    private static final Field<UUID> PG_EVENT_ID = DSL.field(DSL.name(SCHEMA, TABLE_NAME, "event_id"), UUID.class);
    private static final Field<UUID> PG_ACTION_ID = DSL.field(DSL.name(SCHEMA, TABLE_NAME, "action_id"), UUID.class);
    private static final Field<ObjectNode> PG_ACTION_PARAMS = DSL.field(
            DSL.name(SCHEMA, TABLE_NAME, "action_params"),
            SQLDataType.JSONB.asConvertedDataType(new JSONBObjectNodeConverter()));
    private static final Field<ObjectNode> PG_PAYLOAD = DSL.field(
            DSL.name(SCHEMA, TABLE_NAME, "payload"),
            SQLDataType.JSONB.asConvertedDataType(new JSONBObjectNodeConverter()));

    private static final Field<UUID> MARIADB_ID = DSL.field(DSL.name(SCHEMA, TABLE_NAME, "id"), UUID.class);
    private static final Field<UUID> MARIADB_EVENT_ID = DSL.field(DSL.name(SCHEMA, TABLE_NAME, "event_id"), UUID.class);
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
    private static final Field<UUID> MYSQL_EVENT_ID = DSL.field(
            DSL.name(SCHEMA, TABLE_NAME, "event_id"),
            SQLDataType.CHAR(36).asConvertedDataType(new UuidStringConverter()));
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
    private final Field<UUID> eventIdField;
    private final Field<UUID> actionIdField;
    private final Field<ObjectNode> actionParamsField;
    private final Field<ObjectNode> payloadField;

    public EventNotificationRepository(DatabaseRegistry databaseRegistry) {
        this.databaseRegistry = Validate.notNull(databaseRegistry, "databaseRegistry cannot be null");
        final var defaultTm = databaseRegistry.defaultTransactionManager();

        if (defaultTm.dialect.family() == SQLDialect.MYSQL) {
            this.idField = MYSQL_ID;
            this.eventIdField = MYSQL_EVENT_ID;
            this.actionIdField = MYSQL_ACTION_ID;
            this.actionParamsField = MYSQL_ACTION_PARAMS;
            this.payloadField = MYSQL_PAYLOAD;
        } else if (defaultTm.dialect.family() == SQLDialect.MARIADB) {
            this.idField = MARIADB_ID;
            this.eventIdField = MARIADB_EVENT_ID;
            this.actionIdField = MARIADB_ACTION_ID;
            this.actionParamsField = MARIADB_ACTION_PARAMS;
            this.payloadField = MARIADB_PAYLOAD;
        } else {
            this.idField = PG_ID;
            this.eventIdField = PG_EVENT_ID;
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
     * Multi-row INSERT into {@code event_notifications}. Empty input is a no-op.
     *
     * <p>Uses {@code ON CONFLICT DO NOTHING} (PG) / {@code INSERT IGNORE} (MySQL/MariaDB) on
     * the {@code (event_id, handler_name)} unique key. The fanout job reads source events
     * from the replica; with replication lag it can momentarily see events that have
     * already been processed (and have notification rows on primary). Letting the database
     * skip the duplicate rows is cheaper and clearer than catching the constraint violation
     * application-side.
     */
    public void addAllNoResult(Collection<EventNotification> notifications, ShardIdentifier shard) {
        if (notifications.isEmpty()) return;
        final var insert = txDbElseDb(shard)
                .insertInto(
                        NOTIFS,
                        idField,
                        eventIdField,
                        HANDLER_NAME,
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
                        STATE,
                        ATTEMPTS,
                        NEXT_RETRY_AT,
                        CREATED_DATE,
                        UPDATED_DATE);
        for (var n : notifications) {
            insert.values(
                    n.id,
                    n.eventId,
                    n.handlerName,
                    n.namespace,
                    n.actionId,
                    n.actionName,
                    n.actionParams,
                    n.startedDate,
                    n.completionDate,
                    n.modelId,
                    n.modelType,
                    n.eventType,
                    n.payload,
                    n.eventDate,
                    n.state.name(),
                    n.attempts,
                    n.nextRetryAt,
                    n.createdDate,
                    n.updatedDate);
        }
        insert.onConflictDoNothing().execute();
    }

    /**
     * Read up to {@code limit} due-or-overdue notifications (state PENDING or FAILED with
     * {@code next_retry_at <= now}), ordered by {@code next_retry_at} ascending. Each row
     * is fully self-contained — the dispatch job invokes the handler from this alone with
     * no further DB reads.
     */
    public List<EventNotification> findDue(ShardIdentifier shard, int limit, Instant now) {
        return readonlyDb(shard)
                .select(
                        idField,
                        eventIdField,
                        HANDLER_NAME,
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
                        STATE,
                        ATTEMPTS,
                        NEXT_RETRY_AT,
                        CREATED_DATE,
                        UPDATED_DATE)
                .from(NOTIFS)
                .where(STATE.in(EventNotificationState.PENDING.name(), EventNotificationState.FAILED.name()))
                .and(NEXT_RETRY_AT.le(now))
                .orderBy(NEXT_RETRY_AT)
                .limit(limit)
                .fetch()
                .map(r -> EventNotification.eventNotification()
                        .id(r.get(idField))
                        .eventId(r.get(eventIdField))
                        .handlerName(r.get(HANDLER_NAME))
                        .namespace(r.get(NAMESPACE))
                        .actionId(r.get(actionIdField))
                        .actionName(r.get(ACTION_NAME))
                        .actionParams(r.get(actionParamsField))
                        .startedDate(r.get(STARTED_DATE))
                        .completionDate(r.get(COMPLETION_DATE))
                        .modelId(r.get(MODEL_ID))
                        .modelType(r.get(MODEL_TYPE))
                        .eventType(r.get(EVENT_TYPE))
                        .payload(r.get(payloadField))
                        .eventDate(r.get(EVENT_DATE))
                        .state(EventNotificationState.valueOf(r.get(STATE)))
                        .attempts(r.get(ATTEMPTS))
                        .nextRetryAt(r.get(NEXT_RETRY_AT))
                        .createdDate(r.get(CREATED_DATE))
                        .updatedDate(r.get(UPDATED_DATE))
                        .build());
    }

    /**
     * Mark a set of notifications as SUCCEEDED, incrementing {@code attempts} by one for each
     * row in SQL. Empty input is a no-op.
     */
    public void markSucceededAll(Collection<UUID> ids, Instant now, ShardIdentifier shard) {
        if (ids.isEmpty()) return;
        txDbElseDb(shard)
                .update(NOTIFS)
                .set(STATE, EventNotificationState.SUCCEEDED.name())
                .set(ATTEMPTS, ATTEMPTS.plus(1))
                .set(UPDATED_DATE, now)
                .where(idField.in(ids))
                .execute();
    }

    /**
     * Mark a bucket of notifications (all sharing the same {@code next_retry_at}) as FAILED,
     * incrementing {@code attempts} by one for each row in SQL. Caller buckets by the rows'
     * existing {@code attempts} so each bucket can use a single resolved retry timestamp.
     * Empty input is a no-op.
     */
    public void markFailedBucket(Collection<UUID> ids, Instant nextRetryAt, Instant now, ShardIdentifier shard) {
        if (ids.isEmpty()) return;
        txDbElseDb(shard)
                .update(NOTIFS)
                .set(STATE, EventNotificationState.FAILED.name())
                .set(ATTEMPTS, ATTEMPTS.plus(1))
                .set(NEXT_RETRY_AT, nextRetryAt)
                .set(UPDATED_DATE, now)
                .where(idField.in(ids))
                .execute();
    }

    /**
     * Mark notifications as EXPIRED via the pre-flight cap check. {@code attempts} is left
     * unchanged because the handler was never invoked for these rows. Empty input is a no-op.
     */
    public void markExpiredAllPreflight(Collection<UUID> ids, Instant now, ShardIdentifier shard) {
        if (ids.isEmpty()) return;
        txDbElseDb(shard)
                .update(NOTIFS)
                .set(STATE, EventNotificationState.EXPIRED.name())
                .set(UPDATED_DATE, now)
                .where(idField.in(ids))
                .execute();
    }

    /**
     * Mark notifications as EXPIRED after a failed handler invocation whose proposed retry
     * would have landed past the retention deadline. {@code attempts} is incremented by one
     * in SQL. Empty input is a no-op.
     */
    public void markExpiredAllPostFailure(Collection<UUID> ids, Instant now, ShardIdentifier shard) {
        if (ids.isEmpty()) return;
        txDbElseDb(shard)
                .update(NOTIFS)
                .set(STATE, EventNotificationState.EXPIRED.name())
                .set(ATTEMPTS, ATTEMPTS.plus(1))
                .set(UPDATED_DATE, now)
                .where(idField.in(ids))
                .execute();
    }
}
