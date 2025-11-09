package io.ekbatan.core.repository.jooq;

import io.ekbatan.core.domain.Model;
import io.ekbatan.core.persistence.PersistenceException;
import io.ekbatan.core.repository.jooq.exception.EntityNotFoundException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.apache.commons.lang3.Validate;
import org.jooq.*;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.postgresql.util.PSQLException;

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

    protected final DSLContext dsl;
    protected final TABLE table;
    protected final TableField<RECORD, TABLE_ID> idField;
    protected TableField<RECORD, Instant> createdDateField;
    protected TableField<RECORD, Instant> updatedDateField;
    protected final Class<MODEL> modelClass;
    protected final int defaultLimit = 1000;

    // Field names for dynamic access
    private static final String CREATED_DATE_FIELD_NAME = "created_date";
    private static final String UPDATED_DATE_FIELD_NAME = "updated_date";

    protected JooqBaseModelRepository(
            Class<MODEL> modelClass, TABLE table, TableField<RECORD, TABLE_ID> idField, DSLContext dsl) {
        this.dsl = dsl;
        this.table = table;
        this.idField = idField;
        this.createdDateField = Validate.notNull(
                resolveField(table, CREATED_DATE_FIELD_NAME, Instant.class),
                "Table " + table.getName() + " must have a 'created_date' field");
        this.updatedDateField = Validate.notNull(
                resolveField(table, UPDATED_DATE_FIELD_NAME, Instant.class),
                "Table " + table.getName() + " must have an 'updated_date' field for optimistic locking");
        this.modelClass = Validate.notNull(modelClass, "Model class cannot be null");
    }

    public abstract MODEL fromRecord(RECORD record);

    public abstract RECORD toRecord(MODEL model);

    /**
     * Insert a new model.
     *
     * @param model the model to insert
     * @return the inserted model with generated ID
     * @throws PersistenceException if a persistence error occurs
     */
    public MODEL add(MODEL model) {
        return wrapException(
                () -> {
                    Validate.notNull(model, "Model cannot be null");

                    dsl.batchInsert(toRecord(model)).execute();

                    return model;
                },
                model);
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
                        String.format("No entity found with id: %s[id=%s]", modelClass.getSimpleName(), id)));
    }

    @SuppressWarnings("unchecked")
    public List<MODEL> findAllByIds(Collection<TABLE_ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        Set<TABLE_ID> uniqueIds = new LinkedHashSet<>(ids);
        if (uniqueIds.size() <= 3) {
            return dsl.selectFrom(table).where(idField.in(uniqueIds)).fetch().map(this::fromRecord);
        }

        final var idClass = (Class<TABLE_ID>) idField.getType();
        TABLE_ID[] array = uniqueIds.toArray((TABLE_ID[]) new Object[0]); // Simple conversion, requires unchecked cast

        // For larger sets, use = ANY(?) for better performance
        return dsl.selectFrom(table)
                .where(idField.equal(DSL.any(DSL.val(array))))
                .fetch()
                .map(this::fromRecord);
    }

    /**
     * Find all models with pagination.
     */
    public List<MODEL> findAll(int offset, int limit) {
        return dsl.selectFrom(table)
                .orderBy(idField)
                .limit(offset, limit)
                .fetch()
                .map(this::fromRecord);
    }

    /**
     * Find all models with default pagination.
     */
    public List<MODEL> findAll() {
        return dsl.selectFrom(table).fetch().map(this::fromRecord);
    }

    /**
     * Find all models matching the given condition with optional sorting and pagination.
     *
     * @param condition  The condition to match
     * @param sortFields Optional sort fields
     * @return A list of matching models
     */
    public List<MODEL> findAllWhere(Condition condition, SortField<?>... sortFields) {
        return findAllWhere(condition, 0, defaultLimit, sortFields);
    }

    /**
     * Find all models matching the given condition with pagination and optional sorting.
     *
     * @param condition  The condition to match
     * @param offset     The offset for pagination
     * @param limit      The maximum number of results to return
     * @param sortFields Optional sort fields
     * @return A list of matching models
     */
    public List<MODEL> findAllWhere(Condition condition, int offset, int limit, SortField<?>... sortFields) {
        var query = dsl.selectFrom(table).where(condition);

        //        if (sortFields != null && sortFields.length > 0) {
        //            query = query.orderBy(sortFields);
        //        }

        return query.limit(offset, limit).fetch().map(this::fromRecord);
    }

    public boolean existsById(TABLE_ID id) {
        return dsl.fetchExists(dsl.selectOne().from(table).where(idField.eq(id)));
    }

    public long count() {
        return dsl.fetchCount(table);
    }

    public long countWhere(Condition condition) {
        return dsl.fetchCount(table, condition);
    }

    /**
     * Find a single model matching the condition.
     *
     * @return Optional containing the found model, or empty if not found
     */
    public Optional<MODEL> findOneWhere(Condition condition) {
        return Optional.ofNullable(dsl.selectFrom(table).where(condition).fetchOne())
                .map(this::fromRecord);
    }

    /**
     * Find all models matching the condition with pagination and sorting.
     */
    public List<MODEL> findAllWhere(Condition condition, int offset, int limit, Collection<SortField<?>> sortFields) {
        var query = dsl.selectFrom(table).where(condition);

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

    /**
     * Find all models matching the condition with default pagination.
     */
    public List<MODEL> findAllWhere(Condition condition) {
        return findAllWhere(condition, 0, defaultLimit, Set.of());
    }

    /**
     * Find all models matching the condition with pagination.
     */
    public List<MODEL> findAllWhere(Condition condition, int offset, int limit) {
        return findAllWhere(condition, offset, limit, Set.of());
    }

    /**
     * Check if any model matches the condition.
     */
    public boolean existsWhere(Condition condition) {
        return dsl.fetchExists(dsl.selectOne().from(table).where(condition));
    }

    /**
     * Helper method to resolve table fields dynamically.
     *
     * @param table     The table to resolve the field from
     * @param fieldName The name of the field to resolve
     * @param fieldType The expected field type
     * @param <F>       The field type
     * @return The resolved table field, or null if not found or type mismatch
     */
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

    /**
     * Wraps a database operation with proper exception handling.
     *
     * @param <T>       the return type
     * @param operation the operation to perform
     * @param model     the model being operated on (can be null for operations without a specific model)
     * @return the result of the operation
     * @throws PersistenceException if a persistence error occurs
     */
    protected <T> T wrapException(Supplier<T> operation, MODEL model) {
        try {
            return operation.get();
        } catch (Exception ex) {
            throw switch (ex) {
                case DataAccessException dae -> handleDataAccessException(dae, model);
                case PSQLException pe -> handlePSQLException(pe, model);
                case RuntimeException re
                when model != null ->
                    new PersistenceException.ModelPersistingGenericException(
                            model.getId().toString(), re);
                case Throwable t -> new PersistenceException.PersistingGenericException(t);
            };
        }
    }

    /**
     * Handles DataAccessException with pattern matching.
     */
    private PersistenceException handleDataAccessException(DataAccessException ex, MODEL model) {
        String sqlState = ex.sqlState();
        String constraint = extractConstraintName(ex);
        String modelId = model != null ? model.getId().toString() : null;

        return switch (sqlState) {
            case PersistenceException.PG_UNIQUE_VIOLATION ->
                new PersistenceException.UniqueModelRecordViolationException(modelId, table.getName(), constraint);
            case String s
            when s.startsWith("23") -> // SQL State Class 23: Constraint Violation
                new PersistenceException.ModelRecordConstraintViolationException(modelId, table.getName(), constraint);
            default ->
                model != null
                        ? new PersistenceException.ModelPersistingGenericException(modelId, ex)
                        : new PersistenceException.PersistingGenericException(ex);
        };
    }

    /**
     * Handles PSQLException with pattern matching.
     */
    private PersistenceException handlePSQLException(PSQLException ex, MODEL model) {
        String modelId = model != null ? model.getId().toString() : null;
        String constraint =
                ex.getServerErrorMessage() != null ? ex.getServerErrorMessage().getConstraint() : null;

        return switch (ex.getSQLState()) {
            case PersistenceException.PG_UNIQUE_VIOLATION ->
                new PersistenceException.UniqueModelRecordViolationException(modelId, table.getName(), constraint);
            case String s
            when s.startsWith("23") -> // SQL State Class 23: Constraint Violation
                new PersistenceException.ModelRecordConstraintViolationException(modelId, table.getName(), constraint);
            default ->
                model != null
                        ? new PersistenceException.ModelPersistingGenericException(modelId, ex)
                        : new PersistenceException.PersistingGenericException(ex);
        };
    }

    /**
     * Extracts the constraint name from a DataAccessException.
     */
    private String extractConstraintName(DataAccessException ex) {
        if (ex.getCause() instanceof PSQLException pgEx) {
            return pgEx.getServerErrorMessage() != null
                    ? pgEx.getServerErrorMessage().getConstraint()
                    : null;
        }
        return null;
    }

    /**
     * Wraps database operations with proper exception handling.
     * This is a convenience method that delegates to the main wrapException method.
     */
    protected <T> T wrapException(MODEL model, Supplier<T> supplier) {
        return wrapException(supplier, model);
    }

    /**
     * Wraps database operations that don't have a specific model.
     *
     * @param <T>      the return type
     * @param supplier the operation to perform
     * @return the result of the operation
     * @throws PersistenceException if a persistence error occurs
     */
    protected <T> T wrapException(Supplier<T> supplier) {
        return wrapException(supplier, null);
    }
}
