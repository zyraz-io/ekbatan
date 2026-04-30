package io.ekbatan.spring.fixture;

import io.ekbatan.core.action.Action;
import io.ekbatan.di.EkbatanAction;
import java.security.Principal;
import java.time.Clock;

/**
 * Test-only Action used to verify that {@code @EkbatanAction} beans are registered with
 * prototype scope. Intentionally lives in its own package so the registrar's scanner discovers
 * it via {@code AutoConfigurationPackages}.
 */
@EkbatanAction
public class FixtureAction extends Action<String, String> {

    public FixtureAction(Clock clock) {
        super(clock);
    }

    @Override
    protected String perform(Principal principal, String params) {
        return params;
    }
}
