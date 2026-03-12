package io.ekbatan.core.action;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.Model;
import io.ekbatan.core.domain.ModelEvent;
import java.time.Instant;

enum TestState {
    ACTIVE,
    DELETED
}

class TestCreatedEvent extends ModelEvent<TestModel> {
    public final String name;

    TestCreatedEvent(Id<TestModel> id, String name) {
        super(id.getValue().toString(), TestModel.class);
        this.name = name;
    }
}

class TestDeletedEvent extends ModelEvent<TestModel> {
    TestDeletedEvent(Id<TestModel> id) {
        super(id.getValue().toString(), TestModel.class);
    }
}

class TestModel extends Model<TestModel, Id<TestModel>, TestState> {
    public final String name;

    TestModel(Builder builder) {
        super(builder);
        this.name = builder.name;
    }

    static Builder createTestModel(String name, Instant createdDate) {
        var id = Id.random(TestModel.class);
        return Builder.testModel()
                .id(id)
                .state(TestState.ACTIVE)
                .name(name)
                .createdDate(createdDate)
                .withInitialVersion()
                .withEvent(new TestCreatedEvent(id, name));
    }

    TestModel delete() {
        return copy().state(TestState.DELETED)
                .withEvent(new TestDeletedEvent(id))
                .build();
    }

    @Override
    public Builder copy() {
        return Builder.testModel().copyBase(this).name(name);
    }

    static class Builder extends Model.Builder<Id<TestModel>, Builder, TestModel, TestState> {
        String name;

        static Builder testModel() {
            return new Builder();
        }

        Builder name(String name) {
            this.name = name;
            return self();
        }

        @Override
        public TestModel build() {
            return new TestModel(this);
        }
    }
}
