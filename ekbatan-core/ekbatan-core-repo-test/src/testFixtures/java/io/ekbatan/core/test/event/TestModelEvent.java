package io.ekbatan.core.test.event;

import io.ekbatan.core.domain.ModelEvent;

public class TestModelEvent extends ModelEvent<Object> {

    public final String description;

    public TestModelEvent(String modelId, String description) {
        super(modelId, Object.class);
        this.description = description;
    }
}
