package io.ekbatan.core.action;

import java.security.Principal;

class CreateBothAction extends Action<CreateBothAction.Params, TestModel> {
    record Params(String name, String sku) {}

    CreateBothAction(java.time.Clock clock) {
        super(clock);
    }

    @Override
    protected TestModel perform(Principal principal, Params params) {
        plan.add(TestProduct.createTestProduct(params.sku, clock.instant()).build());
        return plan.add(TestModel.createTestModel(params.name, clock.instant()).build());
    }
}
