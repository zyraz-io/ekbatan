package io.ekbatan.core.test.event;

import io.ekbatan.core.domain.ModelEvent;

public class TestStatusChangedEvent extends ModelEvent<Object> {

    public final String oldStatus;
    public final String newStatus;

    public TestStatusChangedEvent(String modelId, String oldStatus, String newStatus) {
        super(modelId, Object.class);
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
    }
}
