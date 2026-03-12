package io.ekbatan.core.action;

import java.security.Principal;

class DeleteAction extends Action<DeleteAction.Params, TestModel> {
    private final TestModel existing;

    record Params() {}

    DeleteAction(java.time.Clock clock, TestModel existing) {
        super(clock);
        this.existing = existing;
    }

    @Override
    protected TestModel perform(Principal principal, Params params) {
        return plan.update(existing.delete());
    }
}
