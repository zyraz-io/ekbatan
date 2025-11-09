// package com.example.springdd.core.action;
//
// import com.example.springdd.core.domain.Model;
// import com.example.springdd.core.domain.ModelEvent;
// import java.util.ArrayList;
// import java.util.List;
//
/// **
// * Base class for actions that provides common functionality.
// *
// * @param <P> The type of the principal
// * @param <PARAMS> The type of the action parameters
// * @param <RESULT> The type of the action result
// */
// public abstract class BaseAction<P, PARAMS extends Action.Params, RESULT> implements Action<P, PARAMS, RESULT> {
//
//    private final List<ModelEvent<?>> events = new ArrayList<>();
//    private final List<Model<?, ?>> modifiedModels = new ArrayList<>();
//
//    @Override
//    public final ActionResult<RESULT> tryPerform(P principal, PARAMS params) throws Exception {
//        // Clear any previous state
//        events.clear();
//        modifiedModels.clear();
//
//        // Execute the action
//        RESULT result = doExecute(principal, params);
//
//        // Return the result with any events or modified models
//        return ActionResult.of(result, new ArrayList<>(events), new ArrayList<>(modifiedModels));
//    }
//
//    /**
//     * Executes the action logic.
//     *
//     * @param principal The principal performing the action
//     * @param params The action parameters
//     * @return the result of the action
//     * @throws Exception if an error occurs during execution
//     */
//    protected abstract RESULT doExecute(P principal, PARAMS params) throws Exception;
//
//    /**
//     * Adds an event to be published after the action completes successfully.
//     *
//     * @param event The event to publish
//     * @param <ID> The type of the model ID
//     */
//    protected <ID> void addEvent(ModelEvent<ID> event) {
//        events.add(event);
//    }
//
//    /**
//     * Adds a model that has been modified by this action.
//     *
//     * @param model The modified model
//     * @param <ID> The type of the model ID
//     */
//    protected <ID> void addModifiedModel(Model<ID> model) {
//        modifiedModels.add(model);
//    }
//
//    /**
//     * Creates a successful result with the given value.
//     *
//     * @param value The result value
//     * @param <T> The type of the result
//     * @return a successful result
//     */
//    protected <T> ActionResult<T> success(T value) {
//        return ActionResult.success(value);
//    }
//
//    /**
//     * Creates a successful result with the given value and events.
//     *
//     * @param value The result value
//     * @param events The events to publish
//     * @param <T> The type of the result
//     * @return a successful result with events
//     */
//    protected <T> ActionResult<T> withEvents(T value, List<ModelEvent<?>> events) {
//        this.events.addAll(events);
//        return ActionResult.withEvents(value, events);
//    }
//
//    /**
//     * Creates a successful result with the given value and modified models.
//     *
//     * @param value The result value
//     * @param models The modified models
//     * @param <T> The type of the result
//     * @return a successful result with modified models
//     */
//    protected <T> ActionResult<T> withModifiedModels(T value, List<Model<?, ?>> models) {
//        this.modifiedModels.addAll(models);
//        return ActionResult.withModifiedModels(value, models);
//    }
// }
