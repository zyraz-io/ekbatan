package io.ekbatan.core.action;

import java.security.Principal;

class RenameAction extends Action<RenameAction.Params, TestModel> {
    private final TestModel existing;

    record Params(String newName) {}

    RenameAction(java.time.Clock clock, TestModel existing) {
        super(clock);
        this.existing = existing;
    }

    @Override
    protected TestModel perform(Principal principal, Params params) {
        return plan().update(existing.copy().name(params.newName).build());
    }
}
