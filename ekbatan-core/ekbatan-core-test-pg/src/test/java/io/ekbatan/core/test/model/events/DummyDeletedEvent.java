package io.ekbatan.core.test.model.events;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.test.model.Dummy;

public class DummyDeletedEvent extends ModelEvent<Dummy> {

    public DummyDeletedEvent(Id<Dummy> dummyId) {
        super(dummyId.getValue().toString(), Dummy.class);
    }
}
