package io.ekbatan.core.test.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.ekbatan.core.domain.ModelEvent;

public class TestStatusChangedEvent extends ModelEvent<Object> {

    public final String oldStatus;
    public final String newStatus;

    public TestStatusChangedEvent(String modelId, String oldStatus, String newStatus) {
        super(modelId, Object.class);
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
    }

    @JsonCreator
    @SuppressWarnings("unused")
    private TestStatusChangedEvent(
            @JsonProperty("modelId") String modelId,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("oldStatus") String oldStatus,
            @JsonProperty("newStatus") String newStatus) {
        super(modelId, Object.class);
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
    }
}
