package io.ekbatan.core.repository.exception;

/**
 * Thrown by {@code AbstractRepository.getById(...)} and the framework's read paths when an
 * expected row is missing. {@code findById(...)} returns an {@code Optional} instead so the
 * caller can decide how to handle absence; {@code getById(...)} is the "must exist" variant
 * and raises this exception when the row isn't there.
 */
public class EntityNotFoundException extends RuntimeException {

    /**
     * Constructs the exception with a human-readable message.
     *
     * @param message the diagnostic message (typically includes the class name and id).
     */
    public EntityNotFoundException(String message) {
        super(message);
    }
}
