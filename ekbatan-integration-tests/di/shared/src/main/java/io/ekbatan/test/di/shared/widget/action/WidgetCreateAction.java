package io.ekbatan.test.di.shared.widget.action;

import static io.ekbatan.test.di.shared.widget.models.Widget.createWidget;

import io.ekbatan.core.action.Action;
import io.ekbatan.di.EkbatanAction;
import io.ekbatan.test.di.shared.widget.models.Widget;
import java.security.Principal;
import java.time.Clock;

@EkbatanAction
public class WidgetCreateAction extends Action<WidgetCreateAction.Params, Widget> {

    public record Params(String name, String color) {}

    public WidgetCreateAction(Clock clock) {
        super(clock);
    }

    @Override
    protected Widget perform(Principal principal, Params params) {
        var widget =
                createWidget(params.name(), params.color(), clock.instant()).build();
        return plan().add(widget);
    }
}
