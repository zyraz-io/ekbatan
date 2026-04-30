package io.ekbatan.core.repository;

import static java.lang.String.format;

import io.ekbatan.core.domain.Persistable;
import io.ekbatan.core.repository.exception.EntityNotFoundException;
import io.ekbatan.core.repository.exception.StaleRecordException;
import io.ekbatan.core.shard.CrossShardException;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.NoShardingStrategy;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.core.shard.ShardingStrategy;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.Validate;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.RowN;
import org.jooq.SQLDialect;
import org.jooq.Select;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableRecord;
import org.jooq.Update;
import org.jooq.UpdateConditionStep;
import org.jooq.UpdateSetMoreStep;
import org.jooq.UpdateSetStep;
import org.jooq.impl.DSL;

/**
 * Abstract base repository implementation using JOOQ.
 */
public abstract class AbstractRepository<
                PERSISTABLE extends Persistable<?>,
                RECORD extends TableRecord<?>,
                TABLE extends Table<RECORD>,
                DB_ID extends Comparable<?>>
        implements Repository<PERSISTABLE> {

    public final DatabaseRegistry databaseRegistry;
    public final ShardingStrategy<DB_ID> shardingStrategy;
    protected final TABLE table;
    public final TableField<RECORD, DB_ID> idField;
    protected final TableField<RECORD, Long> versionField;
    protected final TableField<RECORD, String> stateField;
    public final Class<PERSISTABLE> domainClass;

    private static final Tracer TRACER = GlobalOpenTelemetry.get().getTracer("io.ekbatan.core", "1.0.0");

    private static final String VERSION_FIELD_NAME = "version";
    private static final String STATE_FIELD_NAME = "state";

    protected AbstractRepository(
            Class<PERSISTABLE> domainClass,
            TABLE table,
            TableField<RECORD, DB_ID> idField,
            DatabaseRegistry databaseRegistry) {
        this(domainClass, table, idField, databaseRegistry, new NoShardingStrategy<>());
    }

    protected AbstractRepository(
            Class<PERSISTABLE> domainClass,
            TABLE table,
            TableField<RECORD, DB_ID> idField,
            DatabaseRegistry databaseRegistry,
            ShardingStrategy<DB_ID> shardingStrategy) {
        this.databaseRegistry = Validate.notNull(databaseRegistry, "databaseRegistry cannot be null");
        this.shardingStrategy = Validate.notNull(shardingStrategy, "shardingStrategy cannot be null");
        this.table = table;
        this.idField = idField;
        this.domainClass = Validate.notNull(domainClass, "domainClass cannot be null");

        this.versionField = Validate.notNull(
                resolveField(table, VERSION_FIELD_NAME, Long.class),
                "Table " + table.getName() + " must have a 'version' field for optimistic locking");

        this.stateField = Validate.notNull(
                resolveField(table, STATE_FIELD_NAME, String.class),
                "Table " + table.getName() + " must have a 'state' field");
    }

    @Override
    public ShardingStrategy<DB_ID> shardingStrategy() {
        return shardingStrategy;
    }

    protected abstract String getDomainTypeName();

    public abstract PERSISTABLE fromRecord(RECORD record);

    public abstract RECORD toRecord(PERSISTABLE domainObject);

    // --- db() variants ---

    protected DSLContext db() {
        return databaseRegistry.primary.get(databaseRegistry.defaultShard);
    }

    protected DSLContext db(DB_ID id) {
        return db(effectiveShard(id));
    }

    protected DSLContext db(PERSISTABLE p) {
        return db(effectiveShard(p));
    }

    protected DSLContext db(ShardIdentifier shard) {
        return databaseRegistry.primary.get(shard);
    }

    protected Collection<DSLContext> dbs() {
        if (shardingStrategy instanceof NoShardingStrategy<?>) {
            return List.of(db());
        }
        return databaseRegistry.primary.values();
    }

    // --- readonlyDb() variants ---

    protected DSLContext readonlyDb() {
        return databaseRegistry.secondary.get(databaseRegistry.defaultShard);
    }

    protected DSLContext readonlyDb(DB_ID id) {
        return readonlyDb(effectiveShard(id));
    }

    protected DSLContext readonlyDb(ShardIdentifier shard) {
        return databaseRegistry.secondary.get(shard);
    }

    protected Collection<DSLContext> readonlyDbs() {
        if (shardingStrategy instanceof NoShardingStrategy<?>) {
            return List.of(readonlyDb());
        }
        return databaseRegistry.secondary.values();
    }

    // --- txDb() variants ---

    protected Optional<DSLContext> txDb() {
        return databaseRegistry.defaultTransactionManager().currentTransactionDbContext();
    }

    protected Optional<DSLContext> txDb(DB_ID id) {
        return txDb(effectiveShard(id));
    }

    protected Optional<DSLContext> txDb(PERSISTABLE p) {
        return txDb(effectiveShard(p));
    }

    protected Optional<DSLContext> txDb(ShardIdentifier shard) {
        return databaseRegistry.transactionManager(shard).currentTransactionDbContext();
    }

    // --- txDbElseDb() variants ---

    protected DSLContext txDbElseDb() {
        return txDb().orElseGet(this::db);
    }

    protected DSLContext txDbElseDb(DB_ID id) {
        return txDb(id).orElseGet(() -> db(id));
    }

    protected DSLContext txDbElseDb(PERSISTABLE p) {
        return txDb(p).orElseGet(() -> db(p));
    }

    protected DSLContext txDbElseDb(ShardIdentifier shard) {
        return txDb(shard).orElseGet(() -> db(shard));
    }

    // --- dialect helper ---

    private SQLDialect dialect(ShardIdentifier shard) {
        return databaseRegistry.transactionManager(shard).dialect;
    }

    // --- CRUD operations ---

    @Override
    public PERSISTABLE add(PERSISTABLE domainObject) {
        Validate.notNull(domainObject, getDomainTypeName() + " cannot be null");
        var shard = effectiveShard(domainObject);
        var dialect = dialect(shard);

        if (dialect.equals(SQLDialect.MYSQL)) {
            txDbElseDb(shard).insertInto(table).set(toRecord(domainObject)).execute();
            return domainObject;
        }

        return txDbElseDb(shard)
                .insertInto(table)
                .set(toRecord(domainObject))
                .returning()
                .fetchOptional()
                .map(this::fromRecord)
                .orElseThrow(() -> new IllegalStateException(
                        "Failed to insert " + getDomainTypeName() + " with ID: " + domainObject.getId()));
    }

    @Override
    public void addNoResult(PERSISTABLE domainObject) {
        Validate.notNull(domainObject, getDomainTypeName() + " cannot be null");
        var shard = effectiveShard(domainObject);
        txDbElseDb(shard).insertInto(table).set(toRecord(domainObject)).execute();
    }

    @Override
    public List<PERSISTABLE> addAll(Collection<PERSISTABLE> domainObjects) {
        Validate.notNull(domainObjects, getDomainTypeName() + "s collection cannot be null");

        if (CollectionUtils.isEmpty(domainObjects)) {
            return List.of();
        }

        if (domainObjects.size() == 1) {
            return List.of(add(domainObjects.iterator().next()));
        }

        final var records = domainObjects.stream()
                .map(d -> {
                    Validate.notNull(d, getDomainTypeName() + " in collection cannot be null");
                    return toRecord(d);
                })
                .toList();

        final var shard = effectiveShard(domainObjects);
        final var dialect = dialect(shard);
        final var fields = records.getFirst().fields();
        final var insert = txDbElseDb(shard).insertInto(table, fields);

        for (RECORD record : records) {
            final var values = Arrays.stream(fields).map(record::get).toArray();
            insert.values(values);
        }

        if (dialect.equals(SQLDialect.MYSQL)) {
            insert.execute();
            return List.copyOf(domainObjects);
        }

        final var addedObjects = insert.returning().fetch().map(this::fromRecord);

        if (addedObjects.size() != domainObjects.size()) {
            throw new IllegalStateException(format(
                    "%s %ss were queried for insert, while %s rows were inserted",
                    domainObjects.size(), getDomainTypeName(), addedObjects.size()));
        }

        return addedObjects;
    }

    @Override
    public void addAllNoResult(Collection<PERSISTABLE> domainObjects) {
        Validate.notNull(domainObjects, getDomainTypeName() + "s collection cannot be null");
        if (CollectionUtils.isEmpty(domainObjects)) {
            return;
        }

        final var span = TRACER.spanBuilder("ekbatan.repository")
                .setAttribute("db.operation.name", "BATCH_INSERT")
                .setAttribute("ekbatan.entity.type", domainClass.getSimpleName())
                .setAttribute("ekbatan.batch.size", domainObjects.size())
                .startSpan();
        try (var _ = span.makeCurrent()) {
            final var records = domainObjects.stream().map(this::toRecord).toList();
            final var fields = records.getFirst().fields();
            final var shard = effectiveShard(domainObjects);
            final var insert = txDbElseDb(shard).insertInto(table, fields);

            records.forEach(record ->
                    insert.values(Arrays.stream(fields).map(record::get).toArray()));
            insert.execute();
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public PERSISTABLE update(PERSISTABLE domainObject) {
        Validate.notNull(domainObject, getDomainTypeName() + " cannot be null");
        Validate.notNull(domainObject.getId(), getDomainTypeName() + " ID cannot be null for update");
        Long currentVersion = domainObject.getVersion();
        Validate.notNull(currentVersion, getDomainTypeName() + " must have a non-null version for optimistic locking");

        final RECORD record = toRecord(domainObject);
        final Long newVersion = currentVersion + 1;
        record.set(versionField, newVersion);
        var shard = effectiveShard(domainObject);
        var dialect = dialect(shard);

        if (dialect.equals(SQLDialect.MYSQL)) {
            final var affectedRows = txDbElseDb(shard)
                    .update(table)
                    .set(record)
                    .where(idField.eq(record.get(idField)))
                    .and(versionField.eq(currentVersion))
                    .execute();

            if (affectedRows == 0) {
                throw new StaleRecordException(format(
                        "%s %s[id=%s, version=%d] was concurrently modified or not found",
                        getDomainTypeName(), domainClass.getSimpleName(), domainObject.getId(), currentVersion));
            }
            return (PERSISTABLE) domainObject.nextVersion();
        }

        var updatedRecord = txDbElseDb(shard)
                .update(table)
                .set(record)
                .where(idField.eq(record.get(idField)))
                .and(versionField.eq(currentVersion))
                .returning()
                .fetchOptional();

        if (updatedRecord.isEmpty()) {
            throw new StaleRecordException(format(
                    "%s %s[id=%s, version=%d] was concurrently modified or not found",
                    getDomainTypeName(), domainClass.getSimpleName(), domainObject.getId(), currentVersion));
        }

        return updatedRecord.map(this::fromRecord).orElseThrow();
    }

    @Override
    public void updateNoResult(PERSISTABLE domainObject) {
        Validate.notNull(domainObject, getDomainTypeName() + " cannot be null");
        Validate.notNull(domainObject.getId(), getDomainTypeName() + " ID cannot be null for update");
        Long currentVersion = domainObject.getVersion();
        Validate.notNull(currentVersion, getDomainTypeName() + " must have a non-null version for optimistic locking");

        final RECORD record = toRecord(domainObject);
        final Long newVersion = currentVersion + 1;
        record.set(versionField, newVersion);

        final var shard = effectiveShard(domainObject);

        int affectedRows = txDbElseDb(shard)
                .update(table)
                .set(record)
                .where(idField.eq(record.get(idField)))
                .and(versionField.eq(currentVersion))
                .execute();

        if (affectedRows > 1) {
            throw new IllegalStateException("More than one row was updated, which was not expected");
        }

        if (affectedRows < 1) {
            throw new StaleRecordException(format(
                    "%s %s[id=%s, version=%d] was concurrently modified or not found",
                    getDomainTypeName(), domainClass.getSimpleName(), domainObject.getId(), currentVersion));
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<PERSISTABLE> updateAll(Collection<PERSISTABLE> domainObjects) {
        Validate.notNull(domainObjects, getDomainTypeName() + "s collection cannot be null");
        if (domainObjects.isEmpty()) {
            return List.of();
        }
        if (domainObjects.size() == 1) {
            return List.of(update(domainObjects.iterator().next()));
        }

        final var shard = effectiveShard(domainObjects);
        final var dialect = dialect(shard);
        final List<PERSISTABLE> updatedObjects;
        if (dialect.equals(SQLDialect.MARIADB) || dialect.equals(SQLDialect.MYSQL)) {
            int affectedRows = buildUpdateAllQueryMariadb(shard, domainObjects).execute();

            if (affectedRows < domainObjects.size()) {
                throw new StaleRecordException(format(
                        "Expected to update %d records but only updated %d", domainObjects.size(), affectedRows));
            }

            updatedObjects = domainObjects.stream()
                    .map(d -> (PERSISTABLE) d.nextVersion())
                    .toList();
        } else {
            var updateQuery = buildUpdateAllQuery(shard, domainObjects).returning();
            updatedObjects = updateQuery.fetch().map(this::fromRecord);
        }

        if (updatedObjects.size() != domainObjects.size()) {
            throw new StaleRecordException(format(
                    "Expected to update %d records but only updated %d", domainObjects.size(), updatedObjects.size()));
        }

        return updatedObjects;
    }

    @Override
    public void updateAllNoResult(Collection<PERSISTABLE> domainObjects) {
        Validate.notNull(domainObjects, getDomainTypeName() + "s collection cannot be null");
        if (domainObjects.isEmpty()) {
            return;
        }

        final var span = TRACER.spanBuilder("ekbatan.repository")
                .setAttribute("db.operation.name", "BATCH_UPDATE")
                .setAttribute("ekbatan.entity.type", domainClass.getSimpleName())
                .setAttribute("ekbatan.batch.size", (long) domainObjects.size())
                .startSpan();
        try (var _ = span.makeCurrent()) {
            if (domainObjects.size() == 1) {
                updateNoResult(domainObjects.iterator().next());
                return;
            }

            int affectedRows;
            var shard = effectiveShard(domainObjects);
            var dialect = dialect(shard);
            if (dialect.equals(SQLDialect.MARIADB) || dialect.equals(SQLDialect.MYSQL)) {
                affectedRows = buildUpdateAllQueryMariadb(shard, domainObjects).execute();
            } else {
                affectedRows = buildUpdateAllQuery(shard, domainObjects).execute();
            }

            if (affectedRows != domainObjects.size()) {
                throw new StaleRecordException(format(
                        "Expected to update %d records but only updated %d", domainObjects.size(), affectedRows));
            }
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private UpdateConditionStep<RECORD> buildUpdateAllQuery(
            ShardIdentifier shard, Collection<PERSISTABLE> domainObjects) {
        final var records = domainObjects.stream().map(this::toRecord).toList();
        final var fields = records.getFirst().fields();

        final var rows = records.stream().map(m -> DSL.row(m.intoArray())).toArray(RowN[]::new);

        final var columnNames = Arrays.stream(fields).map(Field::getName).toArray(String[]::new);

        var values = DSL.values(rows).as("v", columnNames);

        var update = txDbElseDb(shard)
                .update(table)
                .set(versionField, values.field(versionField).plus(1));

        for (var field : fields) {
            if (!field.equals(versionField)) {
                update = setField(update, field, values);
            }
        }

        return update.from(values)
                .where(idField.eq(values.field(idField)))
                .and(versionField.eq(values.field(versionField)));
    }

    @SuppressWarnings("unchecked")
    private Update<?> buildUpdateAllQueryMariadb(ShardIdentifier shard, Collection<PERSISTABLE> domainObjects) {
        final var records = domainObjects.stream().map(this::toRecord).toList();
        final var fields = records.getFirst().fields();

        Select<Record> subquery = null;

        for (var record : records) {
            final var selectFields = new ArrayList<Field<?>>(fields.length);
            for (var field : fields) {
                selectFields.add(DSL.val(record.get(field), field).as(field.getName()));
            }
            final var select = DSL.select(selectFields);
            subquery = (subquery == null) ? select : subquery.unionAll(select);
        }

        final var v = subquery.asTable("v");

        final var joinCondition = idField.eq(v.field(idField.getName(), idField.getType()))
                .and(versionField.eq(v.field(versionField.getName(), versionField.getType())));

        final var joinedTable = table.join(v).on(joinCondition);

        var update = txDbElseDb(shard).update(joinedTable);

        var vVersion = v.field(versionField.getName(), versionField.getType());
        var step = update.set(versionField, vVersion.plus(1));

        for (var field : fields) {
            if (!field.equals(versionField)) {
                step = setField(step, field, v);
            }
        }

        return step;
    }

    public Optional<PERSISTABLE> findById(DB_ID id) {
        return Optional.ofNullable(db(id).selectFrom(table)
                        .where(idField.eq(id).and(notDeleted()))
                        .fetchOne())
                .map(this::fromRecord);
    }

    public PERSISTABLE getById(DB_ID id) {
        return findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        format("No entity found with id: %s[id=%s]", domainClass.getSimpleName(), id)));
    }

    public List<PERSISTABLE> findAllByIds(Collection<DB_ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        final var uniqueIds = new LinkedHashSet<>(ids);
        var idsByShard = groupIdsByShard(uniqueIds);

        return idsByShard.entrySet().stream()
                .flatMap(entry -> {
                    var shard = entry.getKey();
                    var dialect = dialect(shard);
                    var ctx = db(shard);
                    var shardIds = entry.getValue();
                    if (dialect.equals(SQLDialect.MYSQL)) {
                        return ctx
                                .selectFrom(table)
                                .where(idField.in(shardIds))
                                .and(notDeleted())
                                .fetch()
                                .map(this::fromRecord)
                                .stream();
                    }
                    return ctx
                            .selectFrom(table)
                            .where(idField.eq(DSL.any(
                                    DSL.val(shardIds, idField.getDataType().getArrayDataType()))))
                            .and(notDeleted())
                            .fetch()
                            .map(this::fromRecord)
                            .stream();
                })
                .toList();
    }

    @Override
    public List<PERSISTABLE> findAll() {
        return dbs().stream()
                .flatMap(ctx -> ctx.selectFrom(table).where(notDeleted()).fetch().map(this::fromRecord).stream())
                .toList();
    }

    public boolean existsById(DB_ID id) {
        final var shard = effectiveShard(id);
        var ctx = db(shard);
        return ctx.fetchExists(ctx.selectOne().from(table).where(idField.eq(id).and(notDeleted())));
    }

    public long count() {
        return dbs().stream()
                .mapToLong(ctx -> ctx.fetchCount(table, notDeleted()))
                .sum();
    }

    public long countWhere(Condition condition) {
        return dbs().stream()
                .mapToLong(ctx -> ctx.fetchCount(table, condition.and(notDeleted())))
                .sum();
    }

    public Optional<PERSISTABLE> findOneWhere(Condition condition) {
        return dbs().stream()
                .map(ctx ->
                        ctx.selectFrom(table).where(condition.and(notDeleted())).fetchOne())
                .filter(Objects::nonNull)
                .map(this::fromRecord)
                .findFirst();
    }

    public List<PERSISTABLE> findAllWhere(Condition condition) {
        return dbs().stream()
                .flatMap(ctx ->
                        ctx.selectFrom(table).where(condition.and(notDeleted())).fetch().map(this::fromRecord).stream())
                .toList();
    }

    public boolean existsWhere(Condition condition) {
        return dbs().stream()
                .anyMatch(ctx -> ctx.fetchExists(ctx.selectOne().from(table).where(condition.and(notDeleted()))));
    }

    protected ShardIdentifier effectiveShard(PERSISTABLE domainObject) {
        var shard = shardingStrategy.resolveShardIdentifier(domainObject).orElse(databaseRegistry.defaultShard);
        return databaseRegistry.effectiveShard(shard);
    }

    protected ShardIdentifier effectiveShard(DB_ID id) {
        rejectNonIdBasedStrategy();
        var shard = shardingStrategy.resolveShardIdentifierById(id).orElse(databaseRegistry.defaultShard);
        return databaseRegistry.effectiveShard(shard);
    }

    protected ShardIdentifier effectiveShard(Collection<PERSISTABLE> domainObjects) {
        if (shardingStrategy instanceof NoShardingStrategy<?>) {
            return databaseRegistry.defaultShard;
        }
        ShardIdentifier first = null;
        for (var p : domainObjects) {
            var shard = databaseRegistry.effectiveShard(
                    shardingStrategy.resolveShardIdentifier(p).orElse(databaseRegistry.defaultShard));
            if (first == null) {
                first = shard;
            } else if (!first.equals(shard)) {
                throw new CrossShardException(first, shard);
            }
        }
        return first != null ? first : databaseRegistry.defaultShard;
    }

    private Map<ShardIdentifier, Collection<DB_ID>> groupIdsByShard(Collection<DB_ID> ids) {
        var result = new LinkedHashMap<ShardIdentifier, Collection<DB_ID>>();
        for (var id : ids) {
            final var shard = effectiveShard(id);
            result.computeIfAbsent(shard, _ -> new ArrayList<>()).add(id);
        }
        return result;
    }

    private void rejectNonIdBasedStrategy() {
        if (shardingStrategy instanceof NoShardingStrategy<?>) {
            return;
        }
        if (!shardingStrategy.usesShardAwareId()) {
            throw new UnsupportedOperationException(
                    "Sharding strategy does not support ID-based shard resolution. Override this method in your repository subclass.");
        }
    }

    private Condition notDeleted() {
        return stateField.ne("DELETED");
    }

    @SuppressWarnings("unchecked")
    protected <F> TableField<RECORD, F> resolveField(Table<RECORD> table, String fieldName, Class<F> fieldType) {
        Field<?> field = table.field(fieldName);
        if (field == null) {
            return null;
        }

        if (field instanceof TableField) {
            final var tableField = (TableField<RECORD, ?>) field;
            if (fieldType.isAssignableFrom(tableField.getType())) {
                return (TableField<RECORD, F>) tableField;
            }
        }

        return null;
    }

    private <T, R extends Record> UpdateSetMoreStep<R> setField(
            UpdateSetStep<R> update, Field<T> targetField, Table<?> values) {
        final var sourceField = values.field(targetField.getName(), targetField.getType());
        return update.set(targetField, sourceField);
    }
}
