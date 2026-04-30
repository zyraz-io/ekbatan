package io.ekbatan.test.local_event_handler.widget.action;

import static io.ekbatan.test.local_event_handler.widget.models.Widget.createWidget;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.test.local_event_handler.widget.models.Widget;
import java.security.Principal;
import java.time.Clock;

public class WidgetCreateAction extends Action<WidgetCreateAction.Params, Widget> {

    public record Params(ShardIdentifier shard, String name, String color) {}

    public WidgetCreateAction(Clock clock) {
        super(clock);
    }

    @Override
    protected Widget perform(Principal principal, Params params) {
        final var widget = createWidget(params.shard(), params.name(), params.color(), clock.instant())
                .build();
        return plan().add(widget);
    }
}
