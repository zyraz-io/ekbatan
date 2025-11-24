package io.ekbatan.core.repository.jooq.exception;

import java.util.Set;
import java.util.UUID;

public class PersistenceException extends RuntimeException {

    public PersistenceException(String message) {
        super(message);
    }

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    // Interface for exceptions that are aware of model IDs
    public interface ModelAware {
        Set<?> getModelIds();
    }

    // Interface for constraint violation exceptions
    public interface ConstraintViolation {
        String getConstraintName();
    }

    // Base class for constraint violation exceptions
    public abstract static class ConstraintViolationException extends PersistenceException
            implements ConstraintViolation, ModelAware {

        private final String constraintName;

        protected ConstraintViolationException(String message, String constraintName) {
            super(message);
            this.constraintName = constraintName;
        }

        @Override
        public String getConstraintName() {
            return constraintName;
        }
    }

    // Unique constraint violation
    public static class UniqueModelRecordViolationException extends ConstraintViolationException {
        private final Object modelId;
        private final String tableName;

        public UniqueModelRecordViolationException(Object modelId, String tableName, String constraintName) {
            super(
                    String.format(
                            "Uniqueness of [%s]:[%s] violated%s",
                            tableName, modelId, constraintName != null ? ": [" + constraintName + "]" : ""),
                    constraintName);
            this.modelId = modelId;
            this.tableName = tableName;
        }

        @Override
        public Set<Object> getModelIds() {
            return Set.of(modelId);
        }

        public Object getModelId() {
            return modelId;
        }

        public String getTableName() {
            return tableName;
        }
    }

    // General constraint violation
    public static class ModelRecordConstraintViolationException extends ConstraintViolationException {
        private final Object modelId;
        private final String tableName;

        public ModelRecordConstraintViolationException(Object modelId, String tableName, String constraintName) {
            super(
                    String.format(
                            "Constraint for [%s]:[%s] violated%s",
                            tableName, modelId, constraintName != null ? ": [" + constraintName + "]" : ""),
                    constraintName);
            this.modelId = modelId;
            this.tableName = tableName;
        }

        @Override
        public Set<Object> getModelIds() {
            return Set.of(modelId);
        }

        public Object getModelId() {
            return modelId;
        }

        public String getTableName() {
            return tableName;
        }
    }

    // Unique UoW event record violation
    public static class UniqueUowEventRecordViolationException extends ConstraintViolationException {
        private final UUID uowId;
        private final String uowName;
        private final UUID idempotencyKey;

        public UniqueUowEventRecordViolationException(
                UUID uowId, String uowName, UUID idempotencyKey, String constraintName) {
            super(
                    String.format(
                            "Uniqueness of [%s] [%s]%s violated%s",
                            uowName,
                            uowId,
                            idempotencyKey != null ? ": [" + idempotencyKey + "]" : "",
                            constraintName != null ? ": [" + constraintName + "]" : ""),
                    constraintName);
            this.uowId = uowId;
            this.uowName = uowName;
            this.idempotencyKey = idempotencyKey;
        }

        public UUID getUowId() {
            return uowId;
        }

        public String getUowName() {
            return uowName;
        }

        public UUID getIdempotencyKey() {
            return idempotencyKey;
        }

        @Override
        public Set<Object> getModelIds() {
            return Set.of(uowId);
        }
    }

    // Stale record exception
    public static class StaleRecordException extends PersistenceException implements ModelAware {
        private final Set<?> modelIds;
        private final String tableName;

        public StaleRecordException(Set<?> modelIds, String tableName) {
            super(formatMessage(modelIds, tableName));
            this.modelIds = Set.copyOf(modelIds);
            this.tableName = tableName;
        }

        public StaleRecordException(Object modelId, String tableName) {
            this(Set.of(modelId), tableName);
        }

        @Override
        public Set<?> getModelIds() {
            return modelIds;
        }

        public String getTableName() {
            return tableName;
        }

        private static String formatMessage(Set<?> modelIds, String tableName) {
            StringBuilder sb = new StringBuilder("Rows for [");
            boolean first = true;
            for (Object id : modelIds) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(tableName).append(": ").append(id);
                first = false;
            }
            sb.append("] were concurrently updated");
            return sb.toString();
        }
    }

    // Model persisting generic exception
    public static class ModelPersistingGenericException extends PersistenceException implements ModelAware {
        private final Object modelId;

        public ModelPersistingGenericException(Object modelId, Throwable cause) {
            super(String.format("Persisting [%s] failed", modelId), cause);
            this.modelId = modelId;
        }

        @Override
        public Set<Object> getModelIds() {
            return Set.of(modelId);
        }

        public Object getModelId() {
            return modelId;
        }
    }

    // Generic persisting exception
    public static class PersistingGenericException extends PersistenceException {
        public PersistingGenericException(Throwable cause) {
            super("Persisting failed", cause);
        }
    }

    // Event payload too large exception
    public static class EventPayloadTooLargeException extends PersistenceException implements ModelAware {
        private final Object modelId;
        private final UUID modelEventId;
        private final UUID eventId;
        private final int payloadSize;
        private final int maxEventPayloadSize;

        public EventPayloadTooLargeException(
                Object modelId, UUID modelEventId, UUID eventId, int payloadSize, int maxEventPayloadSize) {
            super(String.format(
                    "Event [eventId=%s, modelEventId=%s], modelId=%s payload size is %d which exceeds maxEventPayloadSize %d bytes",
                    eventId, modelEventId, modelId, payloadSize, maxEventPayloadSize));
            this.modelId = modelId;
            this.modelEventId = modelEventId;
            this.eventId = eventId;
            this.payloadSize = payloadSize;
            this.maxEventPayloadSize = maxEventPayloadSize;
        }

        @Override
        public Set<Object> getModelIds() {
            return Set.of(modelId);
        }

        public Object getModelId() {
            return modelId;
        }

        public UUID getModelEventId() {
            return modelEventId;
        }

        public UUID getEventId() {
            return eventId;
        }

        public int getPayloadSize() {
            return payloadSize;
        }

        public int getMaxEventPayloadSize() {
            return maxEventPayloadSize;
        }
    }

    // Helper method to check if an exception is a constraint violation
    public static boolean isConstraintViolation(Throwable t) {
        return t instanceof ConstraintViolation;
    }

    // Helper method to check if an exception is a model-aware exception
    public static boolean isModelAware(Throwable t) {
        return t instanceof ModelAware;
    }
}
