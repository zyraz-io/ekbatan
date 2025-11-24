package io.ekbatan.core.repository.jooq;

import java.util.Optional;
import org.jooq.Index;
import org.jooq.Key;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

public class PgHelpers {

    private PgHelpers() {
        // Private constructor to prevent instantiation
    }

    public static Optional<String> extractUniqueConstraintName(Table<?> table, DataAccessException ex) {
        return extractConstraintName(ex).map(constraintName -> table.getKeys().stream()
                .filter(k -> k.getName().equals(constraintName))
                .findFirst()
                .map(Key::getName)
                .orElseGet(() -> table.getIndexes().stream()
                        .filter(i -> i.getUnique() && i.getName().equals(constraintName))
                        .findFirst()
                        .map(Index::getName)
                        .orElse(constraintName)));
    }

    public static Optional<String> extractConstraintName(DataAccessException ex) {
        return Optional.ofNullable(ex.getCause())
                .filter(PSQLException.class::isInstance)
                .map(PSQLException.class::cast)
                .map(PSQLException::getServerErrorMessage)
                .map(ServerErrorMessage::getConstraint);
    }
}
