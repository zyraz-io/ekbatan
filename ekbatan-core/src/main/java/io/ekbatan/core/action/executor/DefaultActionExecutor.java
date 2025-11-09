// package io.ekbatan.core.action.executor;
//
// import io.ekbatan.core.action.Action;
// import io.ekbatan.core.domain.Model;
// import io.ekbatan.core.domain.ModelEvent;
// import java.util.Objects;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;
// import org.springframework.dao.OptimisticLockingFailureException;
// import org.springframework.transaction.PlatformTransactionManager;
// import org.springframework.transaction.TransactionDefinition;
// import org.springframework.transaction.support.DefaultTransactionDefinition;
// import org.springframework.transaction.support.TransactionTemplate;
//
/// **
// * Default implementation of {@link ActionExecutor} that executes actions within a transaction
// * and handles optimistic locking retries.
// */
// public class DefaultActionExecutor implements ActionExecutor {
//    private static final Logger log = LoggerFactory.getLogger(DefaultActionExecutor.class);
//
//    private final PlatformTransactionManager transactionManager;
//    private final TransactionTemplate readOnlyTransactionTemplate;
//    private final TransactionTemplate writeTransactionTemplate;
//    private final EventPublisher eventPublisher;
//    private final ModelPersister modelPersister;
//
//    /**
//     * Creates a new DefaultActionExecutor.
//     *
//     * @param transactionManager The transaction manager to use
//     * @param eventPublisher The event publisher to use
//     * @param modelPersister The model persister to use
//     */
//    public DefaultActionExecutor(
//            PlatformTransactionManager transactionManager,
//            EventPublisher eventPublisher,
//            ModelPersister modelPersister) {
//        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager cannot be null");
//        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher cannot be null");
//        this.modelPersister = Objects.requireNonNull(modelPersister, "modelPersister cannot be null");
//
//        // Configure read-only transaction template
//        DefaultTransactionDefinition readOnlyDefinition = new DefaultTransactionDefinition();
//        readOnlyDefinition.setReadOnly(true);
//        readOnlyDefinition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
//        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager, readOnlyDefinition);
//
//        // Configure write transaction template
//        DefaultTransactionDefinition writeDefinition = new DefaultTransactionDefinition();
//        writeDefinition.setReadOnly(false);
//        writeDefinition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
//        this.writeTransactionTemplate = new TransactionTemplate(transactionManager, writeDefinition);
//    }
//
//    @Override
//    public <P, PARAMS extends Action.Params, RESULT> ActionExecutionResult<RESULT> execute(
//            Action<P, PARAMS, RESULT> action, P principal, PARAMS params) {
//        Objects.requireNonNull(action, "action cannot be null");
//        Objects.requireNonNull(principal, "principal cannot be null");
//        Objects.requireNonNull(params, "params cannot be null");
//
//        try {
//            // Validate parameters
//            params.validate();
//
//            // Execute the action within a transaction
//            Action.ActionResult<RESULT> actionResult = executeInTransaction(action, principal, params);
//
//            // Process the result
//            return processActionResult(actionResult);
//
//        } catch (Exception e) {
//            log.error("Error executing action: {}", action.getClass().getSimpleName(), e);
//            return ActionExecutionResult.failed(e);
//        }
//    }
//
//    @Override
//    public <P, PARAMS extends Action.Params, RESULT> ActionExecutionResult<RESULT> executeWithRetry(
//            Action<P, PARAMS, RESULT> action, P principal, PARAMS params, int maxRetries) {
//        Objects.requireNonNull(action, "action cannot be null");
//        Objects.requireNonNull(principal, "principal cannot be null");
//        Objects.requireNonNull(params, "params cannot be null");
//
//        if (maxRetries < 0) {
//            throw new IllegalArgumentException("maxRetries must be >= 0");
//        }
//
//        int attempt = 0;
//        while (true) {
//            try {
//                return execute(action, principal, params);
//            } catch (OptimisticLockingFailureException e) {
//                if (attempt >= maxRetries) {
//                    log.warn(
//                            "Max retries ({}) reached for action: {}",
//                            maxRetries,
//                            action.getClass().getSimpleName(),
//                            e);
//                    throw e;
//                }
//                attempt++;
//                log.debug("Optimistic lock failure on attempt {}/{}, retrying...", attempt, maxRetries);
//
//                // Add some backoff to prevent tight loops
//                try {
//                    Thread.sleep(100 * attempt);
//                } catch (InterruptedException ie) {
//                    Thread.currentThread().interrupt();
//                    throw new ActionExecutionException("Action execution was interrupted", ie);
//                }
//            }
//        }
//    }
//
//    @Override
//    public void publishEvents(Iterable<ModelEvent<?>> events) {
//        eventPublisher.publish(events);
//    }
//
//    @Override
//    public void persistModels(Iterable<Model<?, ?>> models) {
//        modelPersister.persist(models);
//    }
//
//    private <P, PARAMS extends Action.Params, RESULT> Action.ActionResult<RESULT> executeInTransaction(
//            Action<P, PARAMS, RESULT> action, P principal, PARAMS params) throws Exception {
//        // Determine if this is a read-only action
//        boolean isReadOnly = action instanceof Action.ReadOnly;
//
//        return isReadOnly
//                ? readOnlyTransactionTemplate.execute(status -> {
//                    try {
//                        return action.tryPerform(principal, params);
//                    } catch (Exception e) {
//                        throw new ActionExecutionException("Error executing read-only action", e);
//                    }
//                })
//                : writeTransactionTemplate.execute(status -> {
//                    try {
//                        return action.tryPerform(principal, params);
//                    } catch (Exception e) {
//                        throw new ActionExecutionException("Error executing action", e);
//                    }
//                });
//    }
//
//    private <RESULT> ActionExecutionResult<RESULT> processActionResult(Action.ActionResult<RESULT> actionResult) {
//        if (actionResult == null) {
//            throw new ActionExecutionException("Action returned null result");
//        }
//
//        // Process modified models
//        if (!actionResult.getModifiedModels().isEmpty()) {
//            persistModels(actionResult.getModifiedModels());
//        }
//
//        // Publish events
//        if (!actionResult.getEvents().isEmpty()) {
//            publishEvents(actionResult.getEvents());
//        }
//
//        return ActionExecutionResult.success(actionResult);
//    }
//
//    /**
//     * Interface for publishing domain events.
//     */
//    public interface EventPublisher {
//        /**
//         * Publishes the given events.
//         *
//         * @param events The events to publish
//         */
//        void publish(Iterable<ModelEvent<?>> events);
//    }
//
//    /**
//     * Interface for persisting domain models.
//     */
//    public interface ModelPersister {
//        /**
//         * Persists the given models.
//         *
//         * @param models The models to persist
//         */
//        void persist(Iterable<Model<?, ?>> models);
//    }
// }
