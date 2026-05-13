package io.example.wallet.entity;

import io.ekbatan.core.domain.Entity;
import io.ekbatan.core.domain.Id;
import io.ekbatan.core.processor.AutoBuilder;
import java.util.UUID;
import org.apache.commons.lang3.Validate;

@AutoBuilder
public final class Notification extends Entity<Notification, Id<Notification>, NotificationState> {

    public final UUID walletId;
    public final NotificationKind kind;
    public final String recipient;
    public final String message;

    Notification(NotificationBuilder builder) {
        super(builder);
        this.walletId = Validate.notNull(builder.walletId, "walletId cannot be null");
        this.kind = Validate.notNull(builder.kind, "kind cannot be null");
        this.recipient = Validate.notBlank(builder.recipient, "recipient cannot be blank");
        this.message = Validate.notBlank(builder.message, "message cannot be blank");
    }

    public static NotificationBuilder createNotification(
            UUID walletId, NotificationKind kind, String recipient, String message) {
        return NotificationBuilder.notification()
                .id(Id.random(Notification.class))
                .state(NotificationState.PENDING)
                .walletId(walletId)
                .kind(kind)
                .recipient(recipient)
                .message(message)
                .withInitialVersion();
    }

    @Override
    public NotificationBuilder copy() {
        return NotificationBuilder.notification()
                .copyBase(this)
                .walletId(walletId)
                .kind(kind)
                .recipient(recipient)
                .message(message);
    }

    public Notification markSent() {
        return copy().state(NotificationState.SENT).build();
    }

    public Notification markFailed() {
        return copy().state(NotificationState.FAILED).build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        Notification that = (Notification) o;
        return walletId.equals(that.walletId)
                && kind.equals(that.kind)
                && recipient.equals(that.recipient)
                && message.equals(that.message);
    }
}
