// package com.example.springdd.core.action.executor;
//
// import com.example.springdd.core.action.Action;
// import com.example.springdd.core.domain.Model;
// import com.example.springdd.core.domain.ModelEvent;
//
/// **
// * Executes actions and manages transactions.
// */
// public interface ActionExecutor {
//
//    /**
//     * Executes an action with the given principal and parameters.
//     *
//     * @param <P> The type of the principal
//     * @param <PARAMS> The type of the action parameters
//     * @param <RESULT> The type of the action result
//     * @param action The action to execute
//     * @param principal The principal performing the action
//     * @param params The action parameters
//     * @return the result of the action execution
//     */
//    <P, PARAMS extends Action.Params, RESULT> ActionExecutionResult<RESULT> execute(
//            Action<P, PARAMS, RESULT> action, P principal, PARAMS params);
//
//    /**
//     * Executes an action with the given principal and parameters, with retry on optimistic locking failures.
//     *
//     * @param <P> The type of the principal
//     * @param <PARAMS> The type of the action parameters
//     * @param <RESULT> The type of the action result
//     * @param action The action to execute
//     * @param principal The principal performing the action
//     * @param params The action parameters
//     * @param maxRetries The maximum number of retries on optimistic locking failures
//     * @return the result of the action execution
//     */
//    <P, PARAMS extends Action.Params, RESULT> ActionExecutionResult<RESULT> executeWithRetry(
//            Action<P, PARAMS, RESULT> action, P principal, PARAMS params, int maxRetries);
//
//    /**
//     * Publishes the given events to the event bus.
//     *
//     * @param events The events to publish
//     */
//    void publishEvents(Iterable<ModelEvent<?>> events);
//
//    /**
//     * Persists the given models to the database.
//     *
//     * @param models The models to persist
//     */
//    void persistModels(Iterable<Model<?, ?>> models);
// }
