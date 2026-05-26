package io.ekbatan.core.action.persister.event;

import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.shard.ShardIdentifier;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * Persists the events produced by an action's {@link io.ekbatan.core.domain.Model}s into the
 * outbox. Called from inside the same per-shard transaction as the domain-row writes, so the
 * outbox is always consistent with the data it describes.
 *
 * <p>The default implementation, {@link io.ekbatan.core.action.persister.event.single_table_json.SingleTableJsonEventPersister},
 * writes JSON-serialized events into a single {@code eventlog.events} table. Alternative
 * implementations (separate {@code action_events} + {@code model_events} tables, Avro/Protobuf
 * payloads, etc.) are supplied by setting
 * {@link io.ekbatan.core.action.ActionExecutor.Builder#eventPersister(EventPersister)} at
 * executor-build time.
 *
 * <p>When an action emits zero events, the persister still writes one sentinel row per
 * action so downstream CDC consumers can correlate every action to a row in the eventlog -
 * see the implementation classes for the sentinel encoding.
 */
public interface EventPersister {

    /**
     * Writes the action's events into the outbox. Called from inside the executor's per-shard
     * transaction so the writes are atomic with the domain-row writes.
     *
     * @param namespace the namespace recorded on every persisted event.
     * @param actionName simple class name of the producing action.
     * @param startedDate when the action's {@code perform()} began.
     * @param completionDate when the action's persist phase reached this call.
     * @param actionParams the action's typed params (serialized into the row).
     * @param modelEvents the events produced by the action (possibly empty - a sentinel row may still be written).
     * @param shard the shard the events live on.
     * @param actionEventId executor-generated correlation id stamped on the action-level row.
     */
    void persistActionEvents(
            String namespace,
            String actionName,
            Instant startedDate,
            Instant completionDate,
            Object actionParams,
            Collection<ModelEvent<?>> modelEvents,
            ShardIdentifier shard,
            UUID actionEventId);
}
