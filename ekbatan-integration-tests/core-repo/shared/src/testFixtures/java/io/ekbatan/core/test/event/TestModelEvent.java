package io.ekbatan.core.test.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    /**
     * Jackson deserialization constructor — matches the framework's convention (see
     * e.g. WidgetCreatedEvent) of pairing a public app-facing ctor with a private
     * {@code @JsonCreator} variant that accepts the wire-format fields.
     * The {@code modelName} parameter is consumed for protocol compatibility but ignored
     * because the type binding is fixed to {@code Object.class} by the public ctor.
     */
    @JsonCreator
    @SuppressWarnings("unused")
    private TestModelEvent(
            @JsonProperty("modelId") String modelId,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("description") String description,
            @JsonProperty("eventTime") Instant eventTime,
            @JsonProperty("count") int count,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("referenceId") UUID referenceId,
            @JsonProperty("active") boolean active,
            @JsonProperty("tags") List<String> tags) {
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
