package io.ekbatan.core.action;

import java.security.Principal;

class FailingAction extends Action<FailingAction.Params, TestModel> {
    record Params(String message) {}

    FailingAction(java.time.Clock clock) {
        super(clock);
    }

    @Override
    protected TestModel perform(Principal principal, Params params) throws Exception {
        throw new IllegalArgumentException(params.message);
    }
}
