package io.ekbatan.core.action;

import java.security.Principal;

/**
 * Test-fixture twin of {@code ActionExecutor}. Constructs an {@link ActionPlan} per execution and
 * binds it via {@link Action#runIn(ActionPlan, Principal, Object)} — the same hook the production
 * executor uses — so test code observes the same per-call lifetime as production code.
 *
 * <p>{@code ActionSpec} is the only legitimate way (besides {@code ActionExecutor}) to invoke an
 * {@link Action}. Test authors should not call {@code action.perform(...)} or
 * {@code action.runIn(...)} directly.
 */
public class ActionSpec<P, R> {

    private final Action<P, R> action;
    private Principal principal;

    private ActionSpec(Action<P, R> action) {
        this.action = action;
    }

    public static <P, R> ActionSpec<P, R> of(Action<P, R> action) {
        return new ActionSpec<>(action);
    }

    public ActionSpec<P, R> withPrincipal(Principal principal) {
        this.principal = principal;
        return this;
    }

    public ActionAssert<R> execute(P params) throws Exception {
        var plan = new ActionPlan();
        R result = action.runIn(plan, principal, params);
        return new ActionAssert<>(result, plan);
    }

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
