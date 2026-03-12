package io.ekbatan.core.action;

import java.security.Principal;

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
        R result = action.perform(principal, params);
        return new ActionAssert<>(result, action.plan);
    }

    public <E extends Exception> ActionSpec<P, R> assertThrows(Class<E> exceptionType, P params) {
        try {
            action.perform(principal, params);
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
        try {
            action.perform(principal, params);
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
