package io.ekbatan.spring.fixture;

import io.ekbatan.core.domain.ModelEvent;

/** Test-only ModelEvent used by {@link FixtureEventHandler}. */
public class FixtureEvent extends ModelEvent<FixtureEvent.Placeholder> {

    public FixtureEvent(String modelId) {
        super(modelId, Placeholder.class);
    }

    public static final class Placeholder {}
}
