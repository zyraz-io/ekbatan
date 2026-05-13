package io.example.wallet.handler;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.domain.Id;
import io.ekbatan.di.EkbatanEventHandler;
import io.ekbatan.events.localeventhandler.EventEnvelope;
import io.ekbatan.events.localeventhandler.EventHandler;
import io.example.wallet.action.RefundTransferAction;
import io.example.wallet.model.Wallet;
import io.example.wallet.model.events.TransferFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reacts to a committed {@link TransferFailedEvent} by invoking {@code RefundTransferAction} —
 * the saga's compensation step. The source wallet's balance was reduced by step 1 and step 2
 * couldn't credit the destination, so we credit the source back here.
 */
@EkbatanEventHandler
public class TransferFailedEventHandler implements EventHandler<TransferFailedEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(TransferFailedEventHandler.class);

    private final ActionExecutor executor;

    public TransferFailedEventHandler(ActionExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String name() {
        return "transfer-failed-handler";
    }

    @Override
    public Class<TransferFailedEvent> eventType() {
        return TransferFailedEvent.class;
    }

    @Override
    public void handle(EventEnvelope<TransferFailedEvent> envelope) throws Exception {
        final var e = envelope.event;
        LOG.info("Transfer {} failed ({}); invoking RefundTransferAction", e.transferId, e.reason);
        executor.execute(
                () -> "transfer-saga",
                RefundTransferAction.class,
                new RefundTransferAction.Params(e.transferId, Id.of(Wallet.class, e.fromWalletId), e.amount));
    }
}
