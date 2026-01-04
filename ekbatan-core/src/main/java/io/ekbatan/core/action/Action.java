package io.ekbatan.core.action;

import java.security.Principal;

public abstract class Action<PARAM, RESULT> {
    public final ActionPlan plan = new ActionPlan();

    protected abstract RESULT perform(Principal principal, PARAM params) throws Exception;

    protected void onFailure(Exception e) {
        // Default implementation does nothing
    }
}
