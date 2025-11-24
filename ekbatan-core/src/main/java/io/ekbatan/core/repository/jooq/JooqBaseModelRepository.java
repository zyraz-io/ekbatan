package io.ekbatan.core.repository.jooq;

import static java.lang.String.format;

import io.ekbatan.core.domain.Model;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.repository.jooq.exception.EntityNotFoundException;
import io.ekbatan.core.repository.jooq.exception.StaleRecordException;
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
 * @param <TABLE_ID>     The ID type of the model
 * @param <TABLE>    The JOOQ table type
 */
public abstract class JooqBaseModelRepository<
        MODEL extends Model<MODEL, ?, ?>,
        RECORD extends TableRecord<?>,
        TABLE extends Table<RECORD>,
        TABLE_ID extends Comparable<?>> {

    public final TransactionManager transactionManager;
    private final DSLContext db;
    private final DSLContext readonlyDb;
    protected final TABLE table;
    private final TableField<RECORD, TABLE_ID> idField;
    private final TableField<RECORD, Instant> createdDateField;
    private final TableField<RECORD, Instant> updatedDateField;
    private final Class<MODEL> modelClass;
    protected final int defaultLimit = 1000;

    // Field names for dynamic access
    private static final String CREATED_DATE_FIELD_NAME = "created_date";
    private static final String UPDATED_DATE_FIELD_NAME = "updated_date";

    protected JooqBaseModelRepository(
            Class<MODEL> modelClass,
            TABLE table,
            TableField<RECORD, TABLE_ID> idField,
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
                "Table " + table.getName() + " must have an 'updated_date' field for optimistic locking");
    }

    private DSLContext txDbElseDb() {
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

    /**
     * Insert new models.
     *
     * @param models the models to insert
     * @return the inserted models
     */
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

        final var insert = txDbElseDb().insertInto(table, fields).values(new Object[0]);

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

    public MODEL update(MODEL model) {
        Validate.notNull(model, "Model cannot be null");
        Validate.notNull(model.getId(), "Model ID cannot be null for update");

        final Instant originalUpdatedDate = model.updatedDate;
        Validate.notNull(originalUpdatedDate, "Model must have a non-null updatedDate for optimistic locking check.");

        final RECORD record = toRecord(model);

        final var newUpdatedDate = Instant.now();
        record.set(updatedDateField, newUpdatedDate);

        var updatedRecord = txDbElseDb()
                .update(table)
                .set(record)
                .where(idField.eq((TABLE_ID) model.getId()))
                .and(updatedDateField.eq(originalUpdatedDate))
                .returning()
                .fetchOptional();

        if (updatedRecord.isEmpty()) {
            throw new StaleRecordException(
                    /*format("Entity %s[id=%s] was concurrently modified.", modelClass.getSimpleName(), model.getId())*/ );
        }

        return updatedRecord.map(this::fromRecord).orElseThrow();
    }

    public List<MODEL> updateAll(Collection<MODEL> models) {
        Validate.notNull(models, "Models collection cannot be null");
        if (models.isEmpty()) {
            return List.of();
        }
        if (models.size() == 1) {
            return List.of(update(models.iterator().next()));
        }

        // Validate inputs
        models.forEach(model -> {
            Validate.notNull(model.getId(), "Model ID cannot be null for update");
            Validate.notNull(model.updatedDate, "Model must have a non-null updatedDate for optimistic locking");
        });

        // Build VALUES rows: (id, old_updated_date, new_updated_date)
        List<Row3<TABLE_ID, Instant, Instant>> rows = new ArrayList<>();
        Instant now = Instant.now(); // or generate per model if needed
        for (MODEL m : models) {
            rows.add(DSL.row(
                    (TABLE_ID) m.getId(),
                    m.updatedDate,
                    now // new updated timestamp
            ));
        }

        // values(id, old_updated_date, new_updated_date) AS v(id, old, new)
        var values = DSL
                .values(rows.toArray(Row3[]::new))
                .as("v", "id", "old_updated_date", "new_updated_date");

        DSLContext ctx = txDbElseDb();

        // Build the UPDATE … FROM … RETURNING query
        // NOTE: jOOQ-OSS supports returning() for PostgreSQL
        var updateQuery =
                ctx.update(table)
                        // Set updated_date = v.new_updated_date
                        .set(updatedDateField, DSL.field("v.new_updated_date", Instant.class))
                        // IMPORTANT: Set ALL other fields too (except id, created_date)
                        // We copy values from input model->record into the UPDATE
                        .set(getUpdatableFieldMap(models.iterator().next()))
                        .from(values)
                        .where(idField.eq(DSL.field("v.id", idField.getType())))
                        .and(updatedDateField.eq(DSL.field("v.old_updated_date", Instant.class)))
                        .returning(); // <-- no re-fetch needed

        // Execute the update and get returned records
        List<RECORD> updatedRecords = updateQuery.fetch();

        if (updatedRecords.size() != models.size()) {
            throw new StaleRecordException();
        }

        // Convert returned jOOQ records → MODEL
        List<MODEL> updatedModels = new ArrayList<>(updatedRecords.size());
        for (RECORD r : updatedRecords) {
            updatedModels.add(fromRecord(r));
        }

        return updatedModels;
    }


    private Map<Field<?>, Object> getUpdatableFieldMap(MODEL model) {
        RECORD record = toRecord(model);

        Map<Field<?>, Object> map = new LinkedHashMap<>();
        for (Field<?> field : record.fields()) {
            if (field.equals(idField)) continue;
            if (field.equals(createdDateField)) continue;
            if (field.equals(updatedDateField)) continue;
            map.put(field, record.get(field));
        }
        return map;
    }


    @SuppressWarnings("unchecked")
    public List<MODEL> updateAll2(Collection<MODEL> models) {
        Validate.notNull(models, "Models collection cannot be null");
        if (models.isEmpty()) {
            return List.of();
        }
        if (models.size() == 1) {
            return List.of(update(models.iterator().next()));
        }

        DSLContext ctx = txDbElseDb();
        Instant now = Instant.now();

        // Extract mutable fields dynamically (excluding id, created_date, updated_date)
        List<TableField<RECORD, Object>> mutableFields = new ArrayList<>();
        RECORD firstRecord = toRecord(models.iterator().next());
        for (Field<?> f : firstRecord.fields()) {
            if (f.equals(idField) || f.equals(createdDateField) || f.equals(updatedDateField)) continue;
            mutableFields.add((TableField<RECORD, Object>) f);
        }

        //Build Row objects per model
        List<RowN> rows = new ArrayList<>(models.size());
        for (MODEL m : models) {
            Validate.notNull(m.getId(), "Model ID cannot be null");
            Validate.notNull(m.updatedDate, "Model must have non-null updatedDate");

            RECORD r = toRecord(m);

            // Row: id, old_updated_date, new_updated_date, mutable fields...
            Object[] rowValues = new Object[3 + mutableFields.size()];
            rowValues[0] = m.getId();
            rowValues[1] = m.updatedDate;
            rowValues[2] = now;

            for (int i = 0; i < mutableFields.size(); i++) {
                rowValues[3 + i] = r.get(mutableFields.get(i));
            }

            rows.add(DSL.row(rowValues));
        }

        // Create VALUES table alias
        String[] valueColumnNames = new String[3 + mutableFields.size()];
        valueColumnNames[0] = "id";
        valueColumnNames[1] = "old_updated_date";
        valueColumnNames[2] = "new_updated_date";
        for (int i = 0; i < mutableFields.size(); i++) {
            valueColumnNames[3 + i] = mutableFields.get(i).getName();
        }

        RowN[] rowArray = rows.toArray(new RowN[0]);
        var valuesTable = DSL.values(rowArray)
                .as("v", valueColumnNames);

        // Build UPDATE query
        UpdateSetMoreStep<RECORD> update = ctx.update(table)
                .set(updatedDateField, DSL.field("v.new_updated_date", Instant.class));

        // set all mutable fields dynamically
        for (TableField<RECORD, ?> f : mutableFields) {
            Field<Object> target = (Field<Object>) f;                   // force target to Object
            Object source = DSL.field("v." + f.getName(), f.getType()); // force value to Object
            update.set(target, source);                                // now unambiguous
        }

        // FROM ... WHERE id & optimistic locking
        List<RECORD> updatedRecords = update
                .from(valuesTable)
                .where(idField.eq(DSL.field("v.id", idField.getType())))
                .and(updatedDateField.eq(DSL.field("v.old_updated_date", Instant.class)))
                .returning()
                .fetch();

        // Validate optimistic locking
        if (updatedRecords.size() != models.size()) {
            throw new StaleRecordException();
        }

        // 7️⃣ Convert records to MODEL
        List<MODEL> updatedModels = new ArrayList<>(updatedRecords.size());
        for (RECORD r : updatedRecords) {
            updatedModels.add(fromRecord(r));
        }

        return updatedModels;
    }


    /**
     * Find a single model by ID.
     */
    public Optional<MODEL> findById(TABLE_ID id) {
        return findOneWhere(idField.eq(id));
    }

    public MODEL getById(TABLE_ID id) {
        return findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        format("No entity found with id: %s[id=%s]", modelClass.getSimpleName(), id)));
    }

    @SuppressWarnings("unchecked")
    public List<MODEL> findAllByIds(Collection<TABLE_ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        Set<TABLE_ID> uniqueIds = new LinkedHashSet<>(ids);
        if (uniqueIds.size() <= 3) {
            return readonlyDb()
                    .selectFrom(table)
                    .where(idField.in(uniqueIds))
                    .fetch()
                    .map(this::fromRecord);
        }

        final var idClass = (Class<TABLE_ID>) idField.getType();
        TABLE_ID[] array = uniqueIds.toArray((TABLE_ID[]) new Object[0]); // Simple conversion, requires unchecked cast

        // For larger sets, use = ANY(?) for better performance
        return readonlyDb()
                .selectFrom(table)
                .where(idField.equal(DSL.any(DSL.val(array))))
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

    public boolean existsById(TABLE_ID id) {
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
}
