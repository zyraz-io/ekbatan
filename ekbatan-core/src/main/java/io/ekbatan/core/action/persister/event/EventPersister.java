package io.ekbatan.core.action.persister.event;

import io.ekbatan.core.domain.ModelEvent;
import java.time.Instant;
import java.util.Collection;

public interface EventPersister {

    void persistActionEvents(
            String actionName,
            Instant startedDate,
            Instant completionDate,
            Object actionParams,
            Collection<ModelEvent<?>> modelEvents);
}
