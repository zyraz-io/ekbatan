package io.example.wallet.handler;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.domain.Id;
import io.ekbatan.di.EkbatanEventHandler;
import io.ekbatan.events.localeventhandler.EventEnvelope;
import io.ekbatan.events.localeventhandler.EventHandler;
import io.example.wallet.action.CompleteTransferAction;
import io.example.wallet.model.Wallet;
import io.example.wallet.model.events.TransferInitiatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reacts to a committed {@link TransferInitiatedEvent} by invoking {@code CompleteTransferAction}
 * — the saga's step 2. The in-process local-event-handler picks up the event from the outbox
 * shortly after step 1 commits and chains the next action.
 *
 * <p>Reading all four pieces of saga state ({@code transferId}, {@code fromWalletId},
 * {@code toWalletId}, {@code amount}) directly off the event payload keeps the handler stateless
 * — every retry sees the same envelope, so it's safe to re-invoke {@code CompleteTransferAction}
 * if the previous attempt failed mid-flight.
 */
@EkbatanEventHandler
public class TransferInitiatedEventHandler implements EventHandler<TransferInitiatedEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(TransferInitiatedEventHandler.class);

    private final ActionExecutor executor;

    public TransferInitiatedEventHandler(ActionExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String name() {
        // Cluster-stable identifier; stored in event_notifications.handler_name.
        return "transfer-initiated-handler";
    }

    @Override
    public Class<TransferInitiatedEvent> eventType() {
        return TransferInitiatedEvent.class;
    }

    @Override
    public void handle(EventEnvelope<TransferInitiatedEvent> envelope) throws Exception {
        final var e = envelope.event;
        LOG.info("Transfer {} initiated; invoking CompleteTransferAction", e.transferId);
        executor.execute(
                () -> "transfer-saga",
                CompleteTransferAction.class,
                new CompleteTransferAction.Params(
                        e.transferId,
                        Id.of(Wallet.class, e.fromWalletId),
                        Id.of(Wallet.class, e.toWalletId),
                        e.amount));
    }
}
