package io.ekbatan.core.repository;

import io.ekbatan.core.domain.Model;
import io.ekbatan.core.internal.Validate;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardingStrategy;
import java.time.Instant;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableRecord;

/**
 * Base repository implementation using JOOQ for database operations on Model classes.
 *
 * @param <MODEL>  The domain model type
 * @param <RECORD> The JOOQ record type
 * @param <DB_ID>  The ID type of the model
 * @param <TABLE>  The JOOQ table type
 */
public abstract class ModelRepository<
                MODEL extends Model<MODEL, ?, ?>,
                RECORD extends TableRecord<?>,
                TABLE extends Table<RECORD>,
                DB_ID extends Comparable<DB_ID>>
        extends AbstractRepository<MODEL, RECORD, TABLE, DB_ID> {

    // Field names for dynamic access
    private static final String CREATED_DATE_FIELD_NAME = "created_date";
    private static final String UPDATED_DATE_FIELD_NAME = "updated_date";

    /**
     * Convenience constructor for single-shard model repositories.
     *
     * @param modelClass runtime {@link Class} of the model type.
     * @param table the jOOQ-generated table.
     * @param idField the jOOQ-generated id field.
     * @param databaseRegistry the registry of connection pools / transaction managers.
     */
    protected ModelRepository(
            Class<MODEL> modelClass,
            TABLE table,
            TableField<RECORD, DB_ID> idField,
            DatabaseRegistry databaseRegistry) {
        super(modelClass, table, idField, databaseRegistry);

        Validate.notNull(
                resolveField(table, CREATED_DATE_FIELD_NAME, Instant.class),
                "Table " + table.getName() + " must have a 'created_date' field");
        Validate.notNull(
                resolveField(table, UPDATED_DATE_FIELD_NAME, Instant.class),
                "Table " + table.getName() + " must have an 'updated_date' field");
    }

    /**
     * Primary constructor for sharded model repositories.
     *
     * @param modelClass runtime {@link Class} of the model type.
     * @param table the jOOQ-generated table.
     * @param idField the jOOQ-generated id field.
     * @param databaseRegistry the registry of connection pools / transaction managers.
     * @param shardingStrategy strategy that maps each row's ID to a {@link io.ekbatan.core.shard.ShardIdentifier}.
     */
    protected ModelRepository(
            Class<MODEL> modelClass,
            TABLE table,
            TableField<RECORD, DB_ID> idField,
            DatabaseRegistry databaseRegistry,
            ShardingStrategy<DB_ID> shardingStrategy) {
        super(modelClass, table, idField, databaseRegistry, shardingStrategy);

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
