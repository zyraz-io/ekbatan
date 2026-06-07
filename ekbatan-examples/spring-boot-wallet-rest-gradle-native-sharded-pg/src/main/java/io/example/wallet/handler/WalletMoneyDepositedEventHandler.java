package io.example.wallet.handler;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.di.EkbatanEventHandler;
import io.ekbatan.events.localeventhandler.EventEnvelope;
import io.ekbatan.events.localeventhandler.EventHandler;
import io.example.wallet.action.CreateNotificationAction;
import io.example.wallet.model.NotificationKind;
import io.example.wallet.model.events.WalletMoneyDepositedEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reacts to a committed {@code WalletMoneyDepositedEvent} by creating a Notification row.
 * Demonstrates the framework's listen-to-yourself pattern - the handler runs in-process after the
 * source action commits, then calls another action via the injected {@link ActionExecutor}. The
 * notification is recorded in its own transaction, atomically with a sentinel eventlog row for
 * the {@code CreateNotificationAction} invocation.
 *
 * <p>The recipient isn't part of the {@code WalletMoneyDepositedEvent} payload (that would
 * pollute the domain event with notification-routing concerns). Instead we read it back from
 * {@link EventEnvelope#actionParams} - the serialized parameters of the producing action.
 */
@EkbatanEventHandler
public class WalletMoneyDepositedEventHandler implements EventHandler<WalletMoneyDepositedEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(WalletMoneyDepositedEventHandler.class);

    private final ActionExecutor executor;

    public WalletMoneyDepositedEventHandler(ActionExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String name() {
        // Cluster-stable identifier; stored in event_notifications.handler_name.
        return "wallet-money-deposited-notification";
    }

    @Override
    public Class<WalletMoneyDepositedEvent> eventType() {
        return WalletMoneyDepositedEvent.class;
    }

    @Override
    public void handle(EventEnvelope<WalletMoneyDepositedEvent> envelope) throws Exception {
        final var walletId = UUID.fromString(envelope.event.modelId);
        final var recipient = envelope.actionParams.get("recipient").asText();
        final var message =
                "Deposit of " + envelope.event.amount + " received. New balance: " + envelope.event.newBalance + ".";

        LOG.info("Creating notification for wallet={} recipient={}", walletId, recipient);

        executor.execute(
                () -> "wallet-handler",
                CreateNotificationAction.class,
                new CreateNotificationAction.Params(walletId, NotificationKind.MONEY_DEPOSITED, recipient, message));
    }
}
