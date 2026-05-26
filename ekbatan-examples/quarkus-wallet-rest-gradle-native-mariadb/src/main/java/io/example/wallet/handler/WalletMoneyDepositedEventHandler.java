package io.example.wallet.handler;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.di.EkbatanEventHandler;
import io.ekbatan.events.localeventhandler.EventEnvelope;
import io.ekbatan.events.localeventhandler.EventHandler;
import io.example.wallet.action.CreateNotificationAction;
import io.example.wallet.model.NotificationKind;
import io.example.wallet.model.events.WalletMoneyDepositedEvent;
import jakarta.inject.Inject;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Reacts to a committed {@code WalletMoneyDepositedEvent} by creating a Notification row.
 * Demonstrates the framework's listen-to-yourself pattern - the handler runs in-process after the
 * source action commits, then calls another action via the injected {@link ActionExecutor}. The
 * notification is recorded in its own transaction.
 *
 * <p>The recipient isn't part of the {@code WalletMoneyDepositedEvent} payload (that would
 * pollute the domain event with notification-routing concerns). Instead we read it back from
 * {@link EventEnvelope#actionParams} - the serialized parameters of the producing action.
 */
@EkbatanEventHandler
public class WalletMoneyDepositedEventHandler implements EventHandler<WalletMoneyDepositedEvent> {

    private static final Logger LOG = Logger.getLogger(WalletMoneyDepositedEventHandler.class);

    @Inject
    ActionExecutor executor;

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

        LOG.infof("Creating notification for wallet=%s recipient=%s", walletId, recipient);

        executor.execute(
                () -> "wallet-handler",
                CreateNotificationAction.class,
                new CreateNotificationAction.Params(walletId, NotificationKind.MONEY_DEPOSITED, recipient, message));
    }
}
