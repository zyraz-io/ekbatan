package io.example.wallet.action;

import io.ekbatan.core.action.Action;
import io.ekbatan.di.EkbatanAction;
import io.example.wallet.model.Notification;
import io.example.wallet.model.NotificationKind;
import java.security.Principal;
import java.time.Clock;
import java.util.UUID;

/**
 * Persists a single Notification row. Called by event handlers (listen-to-yourself path) so that
 * notification creation runs in its own action / own transaction, with its own retry policy if
 * the handler invocation fails partway through. Emits no events of its own (Notification is an
 * {@code Entity}, not a {@code Model}) — the eventlog still gets a sentinel row for the action
 * itself.
 */
@EkbatanAction
public class CreateNotificationAction extends Action<CreateNotificationAction.Params, Notification> {

    public record Params(UUID walletId, NotificationKind kind, String recipient, String message) {}

    public CreateNotificationAction(Clock clock) {
        super(clock);
    }

    @Override
    protected Notification perform(Principal principal, Params params) {
        final var notification = Notification.createNotification(
                        params.walletId(), params.kind(), params.recipient(), params.message())
                .build();
        return plan().add(notification);
    }
}
