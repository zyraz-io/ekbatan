package io.ekbatan.core.repository;

import io.ekbatan.core.domain.Model;
import io.ekbatan.core.persistence.TransactionManager;
import java.time.Instant;
import org.apache.commons.lang3.Validate;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableRecord;

/**
 * Base repository implementation using JOOQ for database operations on Model classes.
 *
 * @param <MODEL>    The domain model type
 * @param <RECORD>   The JOOQ record type
 * @param <MODEL_ID> The ID type of the model
 * @param <TABLE>    The JOOQ table type
 */
public abstract class ModelRepository<
                MODEL extends Model<MODEL, ?, ?>,
                RECORD extends TableRecord<?>,
                TABLE extends Table<RECORD>,
                MODEL_ID extends Comparable<MODEL_ID>>
        extends AbstractRepository<MODEL, RECORD, TABLE, MODEL_ID> {

    // Field names for dynamic access
    private static final String CREATED_DATE_FIELD_NAME = "created_date";
    private static final String UPDATED_DATE_FIELD_NAME = "updated_date";

    protected ModelRepository(
            Class<MODEL> modelClass,
            TABLE table,
            TableField<RECORD, MODEL_ID> idField,
            TransactionManager transactionManager) {
        super(modelClass, table, idField, transactionManager);

        Validate.notNull(
                resolveField(table, CREATED_DATE_FIELD_NAME, Instant.class),
                "Table " + table.getName() + " must have a 'created_date' field");
        Validate.notNull(
                resolveField(table, UPDATED_DATE_FIELD_NAME, Instant.class),
                "Table " + table.getName() + " must have an 'updated_date' field");
    }

    @Override
    protected String getDomainTypeName() {
        return "Model";
    }
}
