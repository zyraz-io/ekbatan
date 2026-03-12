package io.ekbatan.core.action;

import java.security.Principal;

class CreateAction extends Action<CreateAction.Params, TestModel> {
    record Params(String name) {}

    CreateAction(java.time.Clock clock) {
        super(clock);
    }

    @Override
    protected TestModel perform(Principal principal, Params params) {
        return plan.add(TestModel.createTestModel(params.name, clock.instant()).build());
    }
}
