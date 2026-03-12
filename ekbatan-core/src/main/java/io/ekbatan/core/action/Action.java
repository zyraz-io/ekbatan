package io.ekbatan.core.action;

import java.security.Principal;
import java.time.Clock;

public abstract class Action<PARAM, RESULT> {
    public final ActionPlan plan = new ActionPlan();
    public final Clock clock;

    protected Action(Clock clock) {
        this.clock = clock;
    }

    protected abstract RESULT perform(Principal principal, PARAM params) throws Exception;
}
