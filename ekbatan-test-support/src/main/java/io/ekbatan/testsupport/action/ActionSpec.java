package io.ekbatan.testsupport.action;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.action.ActionPlan;
import java.security.Principal;

/**
 * Test-support twin of {@code ActionExecutor}. Constructs an {@link ActionPlan} per execution and
 * binds it via {@link Action#runIn(ActionPlan, Principal, Object)} - the same hook the production
 * executor uses - so test code observes the same per-call lifetime as production code.
 *
 * <p>{@code ActionSpec} is the only legitimate way (besides {@code ActionExecutor}) to invoke an
 * {@link Action}. Test authors should not call {@code action.perform(...)} or
 * {@code action.runIn(...)} directly.
 *
 * @param <P> action parameter type.
 * @param <R> action result type.
 */
public class ActionSpec<P, R> {

    private final Action<P, R> action;
    private Principal principal;

    private ActionSpec(Action<P, R> action) {
        this.action = action;
    }

    /**
     * Creates an {@code ActionSpec} for {@code action}.
     *
     * @param <P> action parameter type.
     * @param <R> action result type.
     * @param action action instance to execute.
     * @return an assertion-oriented action spec.
     */
    public static <P, R> ActionSpec<P, R> of(Action<P, R> action) {
        return new ActionSpec<>(action);
    }

    /**
     * Sets the principal passed to {@link Action#perform(Principal, Object)}.
     *
     * @param principal principal to pass to the action.
     * @return this spec.
     */
    public ActionSpec<P, R> withPrincipal(Principal principal) {
        this.principal = principal;
        return this;
    }

    /**
     * Executes the action and returns assertions over the result and staged plan.
     *
     * @param params action parameters.
     * @return assertions over the action result and staged plan.
     * @throws Exception if the action throws.
     */
    public ActionAssert<R> execute(P params) throws Exception {
        var plan = new ActionPlan();
        R result = action.runIn(plan, principal, params);
        return new ActionAssert<>(result, plan);
    }

    /**
     * Asserts that executing the action throws {@code exceptionType}.
     *
     * @param <E> expected exception type.
     * @param exceptionType expected exception class.
     * @param params action parameters.
     * @return this spec.
     */
    public <E extends Exception> ActionSpec<P, R> assertThrows(Class<E> exceptionType, P params) {
        var plan = new ActionPlan();
        try {
            action.runIn(plan, principal, params);
            throw new AssertionError(
                    "Expected " + exceptionType.getSimpleName() + " to be thrown but nothing was thrown");
        } catch (Exception e) {
            if (!exceptionType.isInstance(e)) {
                throw new AssertionError(
                        "Expected " + exceptionType.getSimpleName() + " but got "
                                + e.getClass().getSimpleName(),
                        e);
            }
        }
        return this;
    }

    /**
     * Asserts that executing the action throws {@code exceptionType} and verifies it.
     *
     * @param <E> expected exception type.
     * @param exceptionType expected exception class.
     * @param params action parameters.
     * @param verifier verifier invoked with the thrown exception.
     * @return this spec.
     */
    public <E extends Exception> ActionSpec<P, R> assertThrows(
            Class<E> exceptionType, P params, java.util.function.Consumer<E> verifier) {
        var plan = new ActionPlan();
        try {
            action.runIn(plan, principal, params);
            throw new AssertionError(
                    "Expected " + exceptionType.getSimpleName() + " to be thrown but nothing was thrown");
        } catch (Exception e) {
            if (!exceptionType.isInstance(e)) {
                throw new AssertionError(
                        "Expected " + exceptionType.getSimpleName() + " but got "
                                + e.getClass().getSimpleName(),
                        e);
            }
            verifier.accept(exceptionType.cast(e));
        }
        return this;
    }
}
