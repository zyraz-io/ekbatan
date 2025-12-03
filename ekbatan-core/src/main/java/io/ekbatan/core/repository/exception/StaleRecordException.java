package io.ekbatan.core.repository.exception;

public class StaleRecordException extends RuntimeException {
    public StaleRecordException() {
        super();
    }

    public StaleRecordException(String message) {
        super(message);
    }

    public StaleRecordException(String message, Throwable cause) {
        super(message, cause);
    }

    public StaleRecordException(Throwable cause) {
        super(cause);
    }
}
