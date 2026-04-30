package io.ekbatan.spring.fixture;

import io.ekbatan.di.EkbatanEventHandler;
import io.ekbatan.events.localeventhandler.EventHandler;

/**
 * Test-only {@link EventHandler} used to verify {@code @EkbatanEventHandler} discovery and
 * conditional auto-config wiring of the local-event-handler chain. Not invoked.
 */
@EkbatanEventHandler
public class FixtureEventHandler implements EventHandler<FixtureEvent> {

    @Override
    public String name() {
        return "fixture-event-handler";
    }

    @Override
    public Class<FixtureEvent> eventType() {
        return FixtureEvent.class;
    }

    @Override
    public void handle(FixtureEvent event) {
        // no-op for tests
    }
}
