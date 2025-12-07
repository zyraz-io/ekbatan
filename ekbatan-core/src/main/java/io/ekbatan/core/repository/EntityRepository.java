package io.ekbatan.core.repository;

import static java.lang.String.format;

import io.ekbatan.core.domain.Entity;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.repository.exception.EntityNotFoundException;
import io.ekbatan.core.repository.exception.StaleRecordException;
import java.util.*;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.Validate;
import org.jooq.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/**
 * Base repository implementation using JOOQ for database operations on Entity classes.
 * This includes support for optimistic locking through versioning.
 *
 * @param <ENTITY>    The domain entity type
 * @param <RECORD>    The JOOQ record type
 * @param <ENTITY_ID> The ID type of the entity
 * @param <TABLE>     The JOOQ table type
 */
public abstract class EntityRepository<
                ENTITY extends Entity<ENTITY, ?, ?>,
                RECORD extends TableRecord<?>,
                TABLE extends Table<RECORD>,
                ENTITY_ID extends Comparable<ENTITY_ID>>
        implements Repository {

    public final TransactionManager transactionManager;
    private final DSLContext db;
    private final DSLContext readonlyDb;
    protected final TABLE table;
    private final TableField<RECORD, ENTITY_ID> idField;
    private final TableField<RECORD, Long> versionField;
    private final Class<ENTITY> entityClass;
    protected final int defaultLimit = 1000;
    private static final String VERSION_FIELD_NAME = "version";

    protected EntityRepository(
            Class<ENTITY> entityClass,
            TABLE table,
            TableField<RECORD, ENTITY_ID> idField,
            TransactionManager transactionManager) {
        this.transactionManager = Validate.notNull(transactionManager, "transactionManager cannot be null");
        this.db = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        this.readonlyDb =
                DSL.using(transactionManager.secondaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        this.table = table;
        this.idField = idField;
        this.entityClass = Validate.notNull(entityClass, "entityClass cannot be null");
        this.versionField = resolveField(table, VERSION_FIELD_NAME, Long.class);
        Validate.notNull(
                versionField,
                "Table " + table.getName() + " must have a '" + VERSION_FIELD_NAME + "' field for optimistic locking");
    }

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

    public abstract ENTITY fromRecord(RECORD record);

    public abstract RECORD toRecord(ENTITY entity);

    public ENTITY add(ENTITY entity) {
        Validate.notNull(entity, "Entity cannot be null");

        return txDbElseDb()
                .insertInto(table)
                .set(toRecord(entity))
                .returning()
                .fetchOptional()
                .map(this::fromRecord)
                .orElseThrow(() -> new IllegalStateException("Failed to insert entity with ID: " + entity.id));
    }

    public void addNoResult(ENTITY entity) {
        Validate.notNull(entity, "Entity cannot be null");
        txDbElseDb().insertInto(table).set(toRecord(entity)).execute();
    }

    public List<ENTITY> addAll(Collection<ENTITY> entities) {
        Validate.notNull(entities, "Entities collection cannot be null");

        if (CollectionUtils.isEmpty(entities)) {
            return List.of();
        }

        if (entities.size() == 1) {
            return List.of(add(entities.iterator().next()));
        }

        final var records = entities.stream()
                .map(entity -> {
                    Validate.notNull(entity, "Entity in collection cannot be null");
                    return toRecord(entity);
                })
                .toList();

        final var fields = records.getFirst().fields();
        final var insert = txDbElseDb().insertInto(table, fields);

        for (RECORD record : records) {
            final var values = Arrays.stream(fields).map(record::get).toArray();
            insert.values(values);
        }

        final var addedEntities = insert.returning().fetch().map(this::fromRecord);

        if (addedEntities.size() != entities.size()) {
            throw new IllegalStateException(format(
                    "%s entities were queried for insert, while %s rows were inserted",
                    entities.size(), addedEntities.size()));
        }

        return addedEntities;
    }

    public void addAllNoResult(Collection<ENTITY> entities) {
        Validate.notNull(entities, "Entities collection cannot be null");
        if (CollectionUtils.isEmpty(entities)) {
            return;
        }

        final var records = entities.stream().map(this::toRecord).toList();
        final var fields = records.getFirst().fields();
        final var insert = txDbElseDb().insertInto(table, fields);

        records.forEach(
                record -> insert.values(Arrays.stream(fields).map(record::get).toArray()));
        insert.execute();
    }

    public ENTITY update(ENTITY entity) {
        Validate.notNull(entity, "Entity cannot be null");
        Validate.notNull(entity.getId(), "Entity ID cannot be null for update");
        Validate.notNull(entity.version, "Entity must have a non-null version for optimistic locking");

        final RECORD record = toRecord(entity);
        final Long currentVersion = entity.version;
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
                    "Entity %s[id=%s, version=%d] was concurrently modified or not found",
                    entityClass.getSimpleName(), entity.getId(), currentVersion));
        }

        return updatedRecord.map(this::fromRecord).orElseThrow();
    }

    public void updateNoResult(ENTITY entity) {
        Validate.notNull(entity, "Entity cannot be null");
        Validate.notNull(entity.getId(), "Entity ID cannot be null for update");
        Validate.notNull(entity.version, "Entity must have a non-null version for optimistic locking");

        final RECORD record = toRecord(entity);
        final Long currentVersion = entity.version;
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
                    "Entity %s[id=%s, version=%d] was concurrently modified or not found",
                    entityClass.getSimpleName(), entity.getId(), currentVersion));
        }
    }

    public List<ENTITY> updateAll(Collection<ENTITY> entities) {
        Validate.notNull(entities, "Entities collection cannot be null");
        if (entities.isEmpty()) {
            return List.of();
        }
        if (entities.size() == 1) {
            return List.of(update(entities.iterator().next()));
        }

        var updateQuery = buildUpdateAllQuery(entities).returning();
        final var updatedEntities = updateQuery.fetch().map(this::fromRecord);

        if (updatedEntities.size() != entities.size()) {
            throw new IllegalStateException(format(
                    "Expected to update %d records but only updated %d", entities.size(), updatedEntities.size()));
        }

        return updatedEntities;
    }

    public void updateAllNoResult(Collection<ENTITY> entities) {
        Validate.notNull(entities, "Entities collection cannot be null");
        if (entities.isEmpty()) {
            return;
        }
        if (entities.size() == 1) {
            updateNoResult(entities.iterator().next());
            return;
        }

        int affectedRows = buildUpdateAllQuery(entities).execute();

        if (affectedRows != entities.size()) {
            throw new IllegalStateException(
                    format("Expected to update %d records but only updated %d", entities.size(), affectedRows));
        }
    }

    private UpdateConditionStep<RECORD> buildUpdateAllQuery(Collection<ENTITY> entities) {
        final var records = entities.stream().map(this::toRecord).toList();
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

    public Optional<ENTITY> findById(ENTITY_ID id) {
        return findOneWhere(idField.eq(id));
    }

    public ENTITY getById(ENTITY_ID id) {
        return findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        format("No entity found with id: %s[id=%s]", entityClass.getSimpleName(), id)));
    }

    public List<ENTITY> findAllByIds(Collection<ENTITY_ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        Set<ENTITY_ID> uniqueIds = new LinkedHashSet<>(ids);

        return readonlyDb()
                .selectFrom(table)
                .where(idField.eq(
                        DSL.any(DSL.val(uniqueIds, idField.getDataType().getArrayDataType()))))
                .fetch()
                .map(this::fromRecord);
    }

    public List<ENTITY> findAll(int offset, int limit) {
        return readonlyDb()
                .selectFrom(table)
                .orderBy(idField)
                .limit(offset, limit)
                .fetch()
                .map(this::fromRecord);
    }

    public List<ENTITY> findAll() {
        return db().selectFrom(table).fetch().map(this::fromRecord);
    }

    public List<ENTITY> findAllWhere(Condition condition, SortField<?>... sortFields) {
        return findAllWhere(condition, 0, defaultLimit, sortFields);
    }

    public List<ENTITY> findAllWhere(Condition condition, int offset, int limit, SortField<?>... sortFields) {
        var query = db().selectFrom(table).where(condition);
        return query.limit(offset, limit).fetch().map(this::fromRecord);
    }

    public boolean existsById(ENTITY_ID id) {
        return db().fetchExists(db().selectOne().from(table).where(idField.eq(id)));
    }

    public long count() {
        return db().fetchCount(table);
    }

    public long countWhere(Condition condition) {
        return db().fetchCount(table, condition);
    }

    public Optional<ENTITY> findOneWhere(Condition condition) {
        return Optional.ofNullable(db().selectFrom(table).where(condition).fetchOne())
                .map(this::fromRecord);
    }

    public List<ENTITY> findAllWhere(Condition condition, int offset, int limit, Collection<SortField<?>> sortFields) {
        var query = db().selectFrom(table).where(condition);
        return query.fetch().map(this::fromRecord);
    }

    public List<ENTITY> findAllWhere(Condition condition) {
        return findAllWhere(condition, 0, defaultLimit, Set.of());
    }

    public List<ENTITY> findAllWhere(Condition condition, int offset, int limit) {
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
            TableField<RECORD, ?> tableField = (TableField<RECORD, ?>) field;
            if (fieldType.isAssignableFrom(tableField.getType())) {
                return (TableField<RECORD, F>) tableField;
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private <T> UpdateSetMoreStep<RECORD> setField(
            UpdateSetStep<RECORD> update, Field<T> targetField, Table<?> values) {
        Field<T> sourceField = (Field<T>) values.field(targetField.getName(), targetField.getType());
        return update.set(targetField, sourceField);
    }
}
