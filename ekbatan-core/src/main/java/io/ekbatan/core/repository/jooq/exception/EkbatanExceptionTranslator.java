package io.ekbatan.core.repository.jooq.exception;

import io.ekbatan.core.repository.jooq.PgHelpers;
import org.jooq.*;
import org.jooq.exception.DataAccessException;
import org.postgresql.util.PSQLState;

public class EkbatanExceptionTranslator implements ExecuteListener {

    @Override
    public void exception(ExecuteContext ctx) {

        final var ex = ctx.exception();

        if (ex == null) {
            return;
        }

        final var translation =
                switch (ex) {
                    case DataAccessException dae -> handleDataAccessException(dae);
                    case Throwable t -> new PersistenceException.PersistingGenericException(t);
                };

        ctx.exception(translation);
    }

    private PersistenceException handleDataAccessException(DataAccessException ex) {
        final var psqlState = PSQLState.valueOf(ex.sqlState());

        return switch (psqlState) {
            case PSQLState.UNIQUE_VIOLATION -> {
                final var constraintName = PgHelpers.extractUniqueConstraintName(null, ex);
                yield null;
                //                yield new PersistenceException.UniqueModelRecordViolationException(
                //                                model.getId().toString(),
                //                                table.getName(),
                //                                constraintName
                //                        );
            }
            case PSQLState.NOT_NULL_VIOLATION,
                    PSQLState.FOREIGN_KEY_VIOLATION,
                    PSQLState.CHECK_VIOLATION,
                    PSQLState.EXCLUSION_VIOLATION -> {
                final var constraintName = PgHelpers.extractConstraintName(ex);
                yield null;
                //                yield new PersistenceException.ModelRecordConstraintViolationException(
                //                                model.getId().toString(),
                //                                table.getName(),
                //                                constraintName
                //                        );
            }
            default ->
                new PersistenceException.ModelPersistingGenericException(
                        //                    model.getId().toString(),
                        null, ex);
        };
    }

    private String extractTableName(ExecuteContext ctx) {
        if (ctx.query() == null) {
            return null;
        }

        // Check configuration first
        Object table = ctx.configuration().data("table");

        if (table instanceof Table) {
            return ((Table<?>) table).getName();
        }

        // For TableRecord operations
        if (ctx.query() instanceof TableRecord) {
            return ((TableRecord<?>) ctx.query()).getTable().getName();
        }

        // For queries
        if (ctx.query() instanceof Select) {
            Select<?> select = (Select<?>) ctx.query();
            // Get the first table in the FROM clause
            for (Field<?> field : select.getSelect()) {
                if (field instanceof TableField) {
                    return ((TableField<?, ?>) field).getTable().getName();
                }
            }
        }

        return null;
    }
}
