package io.ekbatan.test.local_event_handler.widget.models;

import static io.ekbatan.test.local_event_handler.widget.models.WidgetState.ACTIVE;

import io.ekbatan.core.domain.Model;
import io.ekbatan.core.domain.ShardedId;
import io.ekbatan.core.processor.AutoBuilder;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.test.local_event_handler.widget.models.events.WidgetCreatedEvent;
import java.time.Instant;
import org.apache.commons.lang3.Validate;

@AutoBuilder
public final class Widget extends Model<Widget, ShardedId<Widget>, WidgetState> {

    public final String name;
    public final String color;

    Widget(WidgetBuilder builder) {
        super(builder);
        this.name = Validate.notBlank(builder.name, "name cannot be blank");
        this.color = Validate.notBlank(builder.color, "color cannot be blank");
    }

    public static WidgetBuilder createWidget(ShardIdentifier shard, String name, String color, Instant createdDate) {
        final var id = ShardedId.generate(Widget.class, shard);
        return WidgetBuilder.widget()
                .id(id)
                .state(ACTIVE)
                .name(name)
                .color(color)
                .createdDate(createdDate)
                .withInitialVersion()
                .withEvent(new WidgetCreatedEvent(id, name, color));
    }

    @Override
    public WidgetBuilder copy() {
        return WidgetBuilder.widget().copyBase(this).name(name).color(color);
    }
}
