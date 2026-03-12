package io.ekbatan.core.action;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.Model;
import io.ekbatan.core.domain.ModelEvent;
import java.time.Instant;

class TestProductCreatedEvent extends ModelEvent<TestProduct> {
    public final String sku;

    TestProductCreatedEvent(Id<TestProduct> id, String sku) {
        super(id.getValue().toString(), TestProduct.class);
        this.sku = sku;
    }
}

class TestProduct extends Model<TestProduct, Id<TestProduct>, TestState> {
    public final String sku;

    TestProduct(Builder builder) {
        super(builder);
        this.sku = builder.sku;
    }

    static Builder createTestProduct(String sku, Instant createdDate) {
        var id = Id.random(TestProduct.class);
        return Builder.testProduct()
                .id(id)
                .state(TestState.ACTIVE)
                .sku(sku)
                .createdDate(createdDate)
                .withInitialVersion()
                .withEvent(new TestProductCreatedEvent(id, sku));
    }

    @Override
    public Builder copy() {
        return Builder.testProduct().copyBase(this).sku(sku);
    }

    static class Builder extends Model.Builder<Id<TestProduct>, Builder, TestProduct, TestState> {
        String sku;

        static Builder testProduct() {
            return new Builder();
        }

        Builder sku(String sku) {
            this.sku = sku;
            return self();
        }

        @Override
        public TestProduct build() {
            return new TestProduct(this);
        }
    }
}
