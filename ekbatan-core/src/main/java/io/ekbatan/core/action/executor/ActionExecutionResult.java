// package io.ekbatan.core.action.executor;
//
// import io.ekbatan.core.action.Action;
// import io.ekbatan.core.domain.Model;
// import io.ekbatan.core.domain.ModelEvent;
// import java.util.List;
//
/// **
// * Represents the result of executing an action.
// *
// * @param <R> The type of the result
// */
// public class ActionExecutionResult<R> {
//    private final R result;
//    private final List<ModelEvent<?>> events;
//    private final List<Model<?, ?>> modifiedModels;
//    private final boolean success;
//    private final Throwable error;
//
//    private ActionExecutionResult(
//            R result, List<ModelEvent<?>> events, List<Model<?, ?>> modifiedModels, boolean success, Throwable error)
// {
//        this.result = result;
//        this.events = events != null ? List.copyOf(events) : List.of();
//        this.modifiedModels = modifiedModels != null ? List.copyOf(modifiedModels) : List.of();
//        this.success = success;
//        this.error = error;
//    }
//
//    /**
//     * Creates a successful execution result.
//     *
//     * @param result The action result
//     * @param <R> The type of the result
//     * @return a successful execution result
//     */
//    public static <R> ActionExecutionResult<R> success(R result) {
//        return new ActionExecutionResult<>(result, List.of(), List.of(), true, null);
//    }
//
//    /**
//     * Creates a successful execution result with events and modified models.
//     *
//     * @param actionResult The action result
//     * @param <R> The type of the result
//     * @return a successful execution result with events and modified models
//     */
//    public static <R> ActionExecutionResult<R> success(Action.ActionResult<R> actionResult) {
//        return new ActionExecutionResult<>(
//                actionResult.getResult(), actionResult.getEvents(), actionResult.getModifiedModels(), true, null);
//    }
//
//    /**
//     * Creates a failed execution result.
//     *
//     * @param error The error that occurred
//     * @param <R> The type of the result
//     * @return a failed execution result
//     */
//    public static <R> ActionExecutionResult<R> failed(Throwable error) {
//        return new ActionExecutionResult<>(null, List.of(), List.of(), false, error);
//    }
//
//    public R getResult() {
//        return result;
//    }
//
//    public List<ModelEvent<?>> getEvents() {
//        return events;
//    }
//
//    public List<Model<?, ?>> getModifiedModels() {
//        return modifiedModels;
//    }
//
//    public boolean isSuccess() {
//        return success;
//    }
//
//    public Throwable getError() {
//        return error;
//    }
// }
