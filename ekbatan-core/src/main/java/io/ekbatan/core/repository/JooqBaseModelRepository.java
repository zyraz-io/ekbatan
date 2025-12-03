package io.ekbatan.core.repository;

import static java.lang.String.format;

import io.ekbatan.core.domain.Model;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.repository.exception.EntityNotFoundException;
import io.ekbatan.core.repository.exception.StaleRecordException;
import java.time.Instant;
import java.util.*;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.Validate;
import org.jooq.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/**
 * Base repository implementation using JOOQ for database operations.
 *
 * @param <MODEL>  The domain model type
 * @param <RECORD> The JOOQ record type
 * @param <MODEL_ID>     The ID type of the model
 * @param <TABLE>    The JOOQ table type
 */
public abstract class JooqBaseModelRepository<
        MODEL extends Model<MODEL, ?, ?>,
        RECORD extends TableRecord<?>,
        TABLE extends Table<RECORD>,
        MODEL_ID extends Comparable<?>> {

    public final TransactionManager transactionManager;
    private final DSLContext db;
    private final DSLContext readonlyDb;
    protected final TABLE table;
    private final TableField<RECORD, MODEL_ID> idField;
    private final TableField<RECORD, Instant> createdDateField;
    private final TableField<RECORD, Instant> updatedDateField;
    private final TableField<RECORD, Long> versionField;
    private final Class<MODEL> modelClass;
    protected final int defaultLimit = 1000;

    // Field names for dynamic access
    private static final String CREATED_DATE_FIELD_NAME = "created_date";
    private static final String UPDATED_DATE_FIELD_NAME = "updated_date";
    private static final String VERSION_FIELD_NAME = "version";

    protected JooqBaseModelRepository(
            Class<MODEL> modelClass,
            TABLE table,
            TableField<RECORD, MODEL_ID> idField,
            TransactionManager transactionManager) {
        this.transactionManager = Validate.notNull(transactionManager, "transactionManager cannot be null");
        this.db = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        this.readonlyDb =
                DSL.using(transactionManager.secondaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        this.table = table;
        this.idField = idField;
        this.modelClass = Validate.notNull(modelClass, "modelClass cannot be null");

        this.createdDateField = Validate.notNull(
                resolveField(table, CREATED_DATE_FIELD_NAME, Instant.class),
                "Table " + table.getName() + " must have a 'created_date' field");
        this.updatedDateField = Validate.notNull(
                resolveField(table, UPDATED_DATE_FIELD_NAME, Instant.class),
                "Table " + table.getName() + " must have an 'updated_date' field");
        this.versionField = Validate.notNull(
                resolveField(table, VERSION_FIELD_NAME, Long.class),
                "Table " + table.getName() + " must have a 'version' field for optimistic locking");
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

    public abstract MODEL fromRecord(RECORD record);

    public abstract RECORD toRecord(MODEL model);

    /**
     * Insert a new model.
     *
     * @param model the model to insert
     * @return the inserted model
     */
    public MODEL add(MODEL model) {
        Validate.notNull(model, "Model cannot be null");

        return txDbElseDb()
                .insertInto(table)
                .set(toRecord(model))
                .returning()
                .fetchOptional()
                .map(this::fromRecord)
                .orElseThrow(() -> new IllegalStateException(
                        model.getId().toString(), new RuntimeException("Failed to insert model")));
    }

    public void addNoResult(MODEL model) {
        Validate.notNull(model, "Model cannot be null");

        txDbElseDb().insertInto(table).set(toRecord(model)).execute();
    }

    public List<MODEL> addAll(Collection<MODEL> models) {
        Validate.notNull(models, "Models collection cannot be null");

        if (CollectionUtils.isEmpty(models)) {
            return List.of();
        }

        if (models.size() == 1) {
            return List.of(add(models.iterator().next()));
        }

        final var records = models.stream()
                .map(model -> {
                    Validate.notNull(model, "Model in collection cannot be null");
                    return toRecord(model);
                })
                .toList();

        final var fields = records.getFirst().fields();

        final var insert = txDbElseDb().insertInto(table, fields);

        for (RECORD record : records) {
            final var values = Arrays.stream(fields).map(record::get).toArray();

            insert.values(values);
        }

        final var addedModels = insert.returning().fetch().map(this::fromRecord);

        if (addedModels.size() != models.size()) {
            throw new IllegalStateException(format(
                    "%s models were queried for insert, while %s rows were inserted",
                    models.size(), addedModels.size()));
        }

        return addedModels;
    }

    public void addAllNoResult(Collection<MODEL> models) {
        Validate.notNull(models, "Models collection cannot be null");
        if (CollectionUtils.isEmpty(models)) {
            return;
        }

        final var records = models.stream().map(this::toRecord).toList();

        final var fields = records.getFirst().fields();
        final var insert = txDbElseDb().insertInto(table, fields);

        records.forEach(
                record -> insert.values(Arrays.stream(fields).map(record::get).toArray()));
        insert.execute();
    }

    public MODEL update(MODEL model) {
        Validate.notNull(model, "Model cannot be null");
        Validate.notNull(model.getId(), "Model ID cannot be null for update");
        Validate.notNull(model.version, "Model must have a non-null version for optimistic locking");

        final RECORD record = toRecord(model);

        // Increment the version
        final Long currentVersion = model.version;
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
                    modelClass.getSimpleName(), model.getId(), currentVersion));
        }

        return updatedRecord.map(this::fromRecord).orElseThrow();
    }

    public void updateNoResult(MODEL model) {
        Validate.notNull(model, "Model cannot be null");
        Validate.notNull(model.getId(), "Model ID cannot be null for update");
        Validate.notNull(model.version, "Model must have a non-null version for optimistic locking");

        final RECORD record = toRecord(model);

        // Increment the version
        final Long currentVersion = model.version;
        final Long newVersion = currentVersion + 1;
        record.set(versionField, newVersion);

        int affectedRows = txDbElseDb()
                .update(table)
                .set(record)
                .where(idField.eq(record.get(idField)))
                .and(versionField.eq(currentVersion))
                .execute();

        if (affectedRows > 1) {
            throw new IllegalStateException();
        }

        if (affectedRows < 1) {
            throw new StaleRecordException(format(
                    "Entity %s[id=%s, version=%d] was concurrently modified or not found",
                    modelClass.getSimpleName(), model.getId(), currentVersion));
        }
    }

    public List<MODEL> updateAll(Collection<MODEL> models) {
        Validate.notNull(models, "Models collection cannot be null");
        if (models.isEmpty()) {
            return List.of();
        }
        if (models.size() == 1) {
            return List.of(update(models.iterator().next()));
        }

        var updateQuery = buildUpdateAllQuery(models).returning();
        final var updatedModels = updateQuery.fetch().map(this::fromRecord);

        if (updatedModels.size() != models.size()) {
            throw new StaleRecordException(
                    format("Expected to update %d records but only updated %d", models.size(), updatedModels.size()));
        }

        return updatedModels;
    }

    public void updateAllNoResult(Collection<MODEL> models) {
        Validate.notNull(models, "Models collection cannot be null");
        if (models.isEmpty()) {
            return;
        }
        if (models.size() == 1) {
            updateNoResult(models.iterator().next());
            return;
        }

        int affectedRows = buildUpdateAllQuery(models).execute();

        if (affectedRows != models.size()) {
            throw new StaleRecordException(
                    format("Expected to update %d records but only updated %d", models.size(), affectedRows));
        }
    }

    private UpdateConditionStep<RECORD> buildUpdateAllQuery(Collection<MODEL> models) {
        final var records = models.stream().map(this::toRecord).toList();
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

    public Optional<MODEL> findById(MODEL_ID id) {
        return findOneWhere(idField.eq(id));
    }

    public MODEL getById(MODEL_ID id) {
        return findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        format("No entity found with id: %s[id=%s]", modelClass.getSimpleName(), id)));
    }

    public List<MODEL> findAllByIds(Collection<MODEL_ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        Set<MODEL_ID> uniqueIds = new LinkedHashSet<>(ids);

        return readonlyDb()
                .selectFrom(table)
                .where(idField.eq(
                        DSL.any(DSL.val(uniqueIds, idField.getDataType().getArrayDataType()))))
                .fetch()
                .map(this::fromRecord);
    }

    /**
     * Find all models with pagination.
     */
    public List<MODEL> findAll(int offset, int limit) {
        return readonlyDb()
                .selectFrom(table)
                .orderBy(idField)
                .limit(offset, limit)
                .fetch()
                .map(this::fromRecord);
    }

    public List<MODEL> findAll() {
        return db().selectFrom(table).fetch().map(this::fromRecord);
    }

    public List<MODEL> findAllWhere(Condition condition, SortField<?>... sortFields) {
        return findAllWhere(condition, 0, defaultLimit, sortFields);
    }

    public List<MODEL> findAllWhere(Condition condition, int offset, int limit, SortField<?>... sortFields) {
        var query = db().selectFrom(table).where(condition);

        //        if (sortFields != null && sortFields.length > 0) {
        //            query = query.orderBy(sortFields);
        //        }

        return query.limit(offset, limit).fetch().map(this::fromRecord);
    }

    public boolean existsById(MODEL_ID id) {
        return db().fetchExists(db().selectOne().from(table).where(idField.eq(id)));
    }

    public long count() {
        return db().fetchCount(table);
    }

    public long countWhere(Condition condition) {
        return db().fetchCount(table, condition);
    }

    public Optional<MODEL> findOneWhere(Condition condition) {
        return Optional.ofNullable(db().selectFrom(table).where(condition).fetchOne())
                .map(this::fromRecord);
    }

    public List<MODEL> findAllWhere(Condition condition, int offset, int limit, Collection<SortField<?>> sortFields) {
        var query = db().selectFrom(table).where(condition);

        //        if (sortFields != null && !sortFields.isEmpty()) {
        //            query = query.orderBy(sortFields);
        //        }
        //
        //        if (offset > 0) {
        //            query = query.offset(offset);
        //        }
        //
        //        if (limit > 0) {
        //            query = query.limit(limit);
        //        }

        return query.fetch().map(this::fromRecord);
    }

    public List<MODEL> findAllWhere(Condition condition) {
        return findAllWhere(condition, 0, defaultLimit, Set.of());
    }

    public List<MODEL> findAllWhere(Condition condition, int offset, int limit) {
        return findAllWhere(condition, offset, limit, Set.of());
    }

    public boolean existsWhere(Condition condition) {
        return db().fetchExists(db().selectOne().from(table).where(condition));
    }

    @SuppressWarnings("unchecked")
    protected <F> TableField<RECORD, F> resolveField(Table<RECORD> table, String fieldName, Class<F> fieldType) {
        Field<?> field = table.field(fieldName);
        if (field == null) {
            return null; // Field doesn't exist
        }

        // Check if the field is a TableField and its type matches the expected type
        if (field instanceof TableField) {
            TableField<RECORD, ?> tableField = (TableField<RECORD, ?>) field;
            if (fieldType.isAssignableFrom(tableField.getType())) {
                return (TableField<RECORD, F>) tableField;
            }
        }

        return null; // Field exists but is not a TableField or type doesn't match
    }

    @SuppressWarnings("unchecked")
    private <T> UpdateSetMoreStep<RECORD> setField(
            UpdateSetStep<RECORD> update, Field<T> targetField, Table<?> values) {
        Field<T> sourceField = (Field<T>) values.field(targetField.getName(), targetField.getType());
        return update.set(targetField, sourceField);
    }
}
