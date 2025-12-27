package io.ekbatan.core.repository;

import static java.lang.String.format;

import io.ekbatan.core.domain.Persistable;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.repository.exception.EntityNotFoundException;
import io.ekbatan.core.repository.exception.StaleRecordException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.Validate;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.RowN;
import org.jooq.SQLDialect;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableRecord;
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
                ID extends Comparable<?>>
        implements Repository<PERSISTABLE> {

    public final TransactionManager transactionManager;
    private final DSLContext db;
    private final DSLContext readonlyDb;
    protected final TABLE table;
    private final TableField<RECORD, ID> idField;
    protected final TableField<RECORD, Long> versionField;
    protected final Class<PERSISTABLE> domainClass;

    private static final String VERSION_FIELD_NAME = "version";

    protected AbstractRepository(
            Class<PERSISTABLE> domainClass,
            TABLE table,
            TableField<RECORD, ID> idField,
            TransactionManager transactionManager) {
        this.transactionManager = Validate.notNull(transactionManager, "transactionManager cannot be null");
        this.db = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        this.readonlyDb =
                DSL.using(transactionManager.secondaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        this.table = table;
        this.idField = idField;
        this.domainClass = Validate.notNull(domainClass, "domainClass cannot be null");

        this.versionField = Validate.notNull(
                resolveField(table, VERSION_FIELD_NAME, Long.class),
                "Table " + table.getName() + " must have a 'version' field for optimistic locking");
    }

    protected abstract String getDomainTypeName();

    public abstract PERSISTABLE fromRecord(RECORD record);

    public abstract RECORD toRecord(PERSISTABLE domainObject);

    protected DSLContext txDbElseDb() {
        return txDb().orElseGet(this::db);
    }

    protected Optional<DSLContext> txDb() {
        return transactionManager.currentTransactionDbContext();
    }

    protected DSLContext db() {
        return db;
    }

    protected DSLContext readonlyDb() {
        return readonlyDb;
    }

    @Override
    public PERSISTABLE add(PERSISTABLE domainObject) {
        Validate.notNull(domainObject, getDomainTypeName() + " cannot be null");

        return txDbElseDb()
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
        txDbElseDb().insertInto(table).set(toRecord(domainObject)).execute();
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

        final var fields = records.getFirst().fields();
        final var insert = txDbElseDb().insertInto(table, fields);

        for (RECORD record : records) {
            final var values = Arrays.stream(fields).map(record::get).toArray();
            insert.values(values);
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

        final var records = domainObjects.stream().map(this::toRecord).toList();
        final var fields = records.getFirst().fields();
        final var insert = txDbElseDb().insertInto(table, fields);

        records.forEach(
                record -> insert.values(Arrays.stream(fields).map(record::get).toArray()));
        insert.execute();
    }

    @Override
    public PERSISTABLE update(PERSISTABLE domainObject) {
        Validate.notNull(domainObject, getDomainTypeName() + " cannot be null");
        Validate.notNull(domainObject.getId(), getDomainTypeName() + " ID cannot be null for update");
        Long currentVersion = domainObject.getVersion();
        Validate.notNull(currentVersion, getDomainTypeName() + " must have a non-null version for optimistic locking");

        final RECORD record = toRecord(domainObject);
        final Long newVersion = currentVersion + 1;
        record.set(versionField, newVersion);

        var updatedRecord = txDbElseDb()
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

        int affectedRows = txDbElseDb()
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
    public List<PERSISTABLE> updateAll(Collection<PERSISTABLE> domainObjects) {
        Validate.notNull(domainObjects, getDomainTypeName() + "s collection cannot be null");
        if (domainObjects.isEmpty()) {
            return List.of();
        }
        if (domainObjects.size() == 1) {
            return List.of(update(domainObjects.iterator().next()));
        }

        var updateQuery = buildUpdateAllQuery(domainObjects).returning();
        final var updatedObjects = updateQuery.fetch().map(this::fromRecord);

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
        if (domainObjects.size() == 1) {
            updateNoResult(domainObjects.iterator().next());
            return;
        }

        int affectedRows = buildUpdateAllQuery(domainObjects).execute();

        if (affectedRows != domainObjects.size()) {
            throw new StaleRecordException(
                    format("Expected to update %d records but only updated %d", domainObjects.size(), affectedRows));
        }
    }

    private UpdateConditionStep<RECORD> buildUpdateAllQuery(Collection<PERSISTABLE> domainObjects) {
        final var records = domainObjects.stream().map(this::toRecord).toList();
        final var fields = records.getFirst().fields();

        final var rows = records.stream().map(m -> DSL.row(m.intoArray())).toArray(RowN[]::new);

        final var columnNames = Arrays.stream(fields).map(Field::getName).toArray(String[]::new);

        var values = DSL.values(rows).as("v", columnNames);

        var update = txDbElseDb()
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

    public Optional<PERSISTABLE> findById(ID id) {
        return findOneWhere(idField.eq(id));
    }

    public PERSISTABLE getById(ID id) {
        return findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        format("No entity found with id: %s[id=%s]", domainClass.getSimpleName(), id)));
    }

    public List<PERSISTABLE> findAllByIds(Collection<ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        final var uniqueIds = new LinkedHashSet<>(ids);

        return readonlyDb()
                .selectFrom(table)
                .where(idField.eq(
                        DSL.any(DSL.val(uniqueIds, idField.getDataType().getArrayDataType()))))
                .fetch()
                .map(this::fromRecord);
    }

    public List<PERSISTABLE> findAll(int offset, int limit) {
        return readonlyDb()
                .selectFrom(table)
                .orderBy(idField)
                .limit(offset, limit)
                .fetch()
                .map(this::fromRecord);
    }

    @Override
    public List<PERSISTABLE> findAll() {
        return db().selectFrom(table).fetch().map(this::fromRecord);
    }

    public List<PERSISTABLE> findAllWhere(Condition condition, SortField<?>... sortFields) {
        var query = db().selectFrom(table).where(condition);

        if (ArrayUtils.isNotEmpty(sortFields)) {
            return query.orderBy(sortFields).fetch().map(this::fromRecord);
        }

        return query.fetch().map(this::fromRecord);
    }

    public List<PERSISTABLE> findAllWhere(Condition condition, int offset, int limit, SortField<?>... sortFields) {
        var query = db().selectFrom(table).where(condition);

        if (ArrayUtils.isNotEmpty(sortFields)) {
            return query.orderBy(sortFields).limit(offset, limit).fetch().map(this::fromRecord);
        }

        return query.limit(offset, limit).fetch().map(this::fromRecord);
    }

    public boolean existsById(ID id) {
        return db().fetchExists(db().selectOne().from(table).where(idField.eq(id)));
    }

    public long count() {
        return db().fetchCount(table);
    }

    public long countWhere(Condition condition) {
        return db().fetchCount(table, condition);
    }

    public Optional<PERSISTABLE> findOneWhere(Condition condition) {
        return Optional.ofNullable(db().selectFrom(table).where(condition).fetchOne())
                .map(this::fromRecord);
    }

    public List<PERSISTABLE> findAllWhere(
            Condition condition, int offset, int limit, Collection<SortField<?>> sortFields) {
        var query = db().selectFrom(table).where(condition);

        if (CollectionUtils.isNotEmpty(sortFields)) {
            return query.orderBy(sortFields).limit(offset, limit).fetch().map(this::fromRecord);
        }

        return query.limit(offset, limit).fetch().map(this::fromRecord);
    }

    public List<PERSISTABLE> findAllWhere(Condition condition) {
        return db().selectFrom(table).where(condition).fetch().map(this::fromRecord);
    }

    public List<PERSISTABLE> findAllWhere(Condition condition, int offset, int limit) {
        return findAllWhere(condition, offset, limit, Set.of());
    }

    public boolean existsWhere(Condition condition) {
        return db().fetchExists(db().selectOne().from(table).where(condition));
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

    @SuppressWarnings("unchecked")
    private <T> UpdateSetMoreStep<RECORD> setField(
            UpdateSetStep<RECORD> update, Field<T> targetField, Table<?> values) {
        final var sourceField = values.field(targetField.getName(), targetField.getType());
        return update.set(targetField, sourceField);
    }
}
