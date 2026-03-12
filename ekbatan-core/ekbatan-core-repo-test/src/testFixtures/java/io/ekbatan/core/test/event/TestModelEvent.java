package io.ekbatan.core.test.event;

import io.ekbatan.core.domain.ModelEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class TestModelEvent extends ModelEvent<Object> {

    public final String description;
    public final Instant eventTime;
    public final int count;
    public final BigDecimal amount;
    public final UUID referenceId;
    public final boolean active;
    public final List<String> tags;

    public TestModelEvent(
            String modelId,
            String description,
            Instant eventTime,
            int count,
            BigDecimal amount,
            UUID referenceId,
            boolean active,
            List<String> tags) {
        super(modelId, Object.class);
        this.description = description;
        this.eventTime = eventTime;
        this.count = count;
        this.amount = amount;
        this.referenceId = referenceId;
        this.active = active;
        this.tags = tags;
    }
}
