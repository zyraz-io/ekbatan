package io.ekbatan.core.action.persister.event;

import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.shard.ShardIdentifier;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

public interface EventPersister {

    void persistActionEvents(
            String actionName,
            Instant startedDate,
            Instant completionDate,
            Object actionParams,
            Collection<ModelEvent<?>> modelEvents,
            ShardIdentifier shard,
            UUID actionEventId);
}
