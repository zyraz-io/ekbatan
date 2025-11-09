// package com.example.springdd.core.action;
//
// import com.example.springdd.core.domain.Model;
// import com.example.springdd.core.domain.ModelEvent;
// import java.io.Serializable;
// import java.util.List;
//
/// **
// * Represents an operation that can be performed on the domain model.
// * Actions are the primary way to modify the state of the system.
// *
// * @param <P> The type of the principal (user or system) performing the action
// * @param <PARAMS> The type of the parameters required to perform the action
// * @param <RESULT> The type of the result returned by the action
// */
// public interface Action<P, PARAMS extends Action.Params, RESULT> {
//
//    /**
//     * Marker interface for actions that only read data and do not modify any state.
//     * Actions implementing this interface will be executed in a read-only transaction.
//     */
//    interface ReadOnly {
//        // Marker interface
//    }
//
//    /**
//     * Attempts to perform the action with the given parameters.
//     * This method should contain the business logic for the action.
//     *
//     * @param principal The principal performing the action
//     * @param params The parameters for the action
//     * @return the result of the action
//     * @throws Exception if the action cannot be performed
//     */
//    ActionResult<RESULT> tryPerform(P principal, PARAMS params) throws Exception;
//
//    /**
//     * Base interface for action parameters.
//     * All action parameter classes must implement this interface.
//     */
//    interface Params extends Serializable {
//        /**
//         * Validates the parameters.
//         *
//         * @throws IllegalArgumentException if the parameters are invalid
//         */
//        default void validate() {
//            // Default implementation does nothing
//        }
//    }
//
//    /**
//     * Represents the result of an action.
//     *
//     * @param <T> The type of the result
//     */
//    class ActionResult<T> {
//        private final T result;
//        private final List<ModelEvent<?>> events;
//        private final List<Model<?, ?>> modifiedModels;
//
//        private ActionResult(T result, List<ModelEvent<?>> events, List<Model<?, ?>> modifiedModels) {
//            this.result = result;
//            this.events = events != null ? List.copyOf(events) : List.of();
//            this.modifiedModels = modifiedModels != null ? List.copyOf(modifiedModels) : List.of();
//        }
//
//        /**
//         * Creates a successful action result with the given value.
//         *
//         * @param value The result value
//         * @param <T> The type of the result
//         * @return a successful action result
//         */
//        public static <T> ActionResult<T> success(T value) {
//            return new ActionResult<>(value, List.of(), List.of());
//        }
//
//        /**
//         * Creates a successful action result with the given value and events.
//         *
//         * @param value The result value
//         * @param events The events that occurred during the action
//         * @param <T> The type of the result
//         * @return a successful action result with events
//         */
//        public static <T> ActionResult<T> withEvents(T value, List<ModelEvent<?>> events) {
//            return new ActionResult<>(value, events, List.of());
//        }
//
//        /**
//         * Creates a successful action result with the given value and modified models.
//         *
//         * @param value The result value
//         * @param modifiedModels The models that were modified during the action
//         * @param <T> The type of the result
//         * @return a successful action result with modified models
//         */
//        public static <T> ActionResult<T> withModifiedModels(T value, List<Model<?, ?>> modifiedModels) {
//            return new ActionResult<>(value, List.of(), modifiedModels);
//        }
//
//        /**
//         * Creates a successful action result with the given value, events, and modified models.
//         *
//         * @param value The result value
//         * @param events The events that occurred during the action
//         * @param modifiedModels The models that were modified during the action
//         * @param <T> The type of the result
//         * @return a successful action result with events and modified models
//         */
//        public static <T> ActionResult<T> of(T value, List<ModelEvent<?>> events, List<Model<?, ?>> modifiedModels) {
//            return new ActionResult<>(value, events, modifiedModels);
//        }
//
//        /**
//         * Returns the result of the action.
//         *
//         * @return the result
//         */
//        public T getResult() {
//            return result;
//        }
//
//        /**
//         * Returns the events that occurred during the action.
//         *
//         * @return the events
//         */
//        public List<ModelEvent<?>> getEvents() {
//            return events;
//        }
//
//        /**
//         * Returns the models that were modified during the action.
//         *
//         * @return the modified models
//         */
//        public List<Model<?, ?>> getModifiedModels() {
//            return modifiedModels;
//        }
//    }
// }
