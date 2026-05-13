package io.example.wallet.repository;

import static io.example.wallet.generated.jooq.default_schema.tables.Notifications.NOTIFICATIONS;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.repository.EntityRepository;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.di.EkbatanRepository;
import io.example.wallet.generated.jooq.default_schema.tables.Notifications;
import io.example.wallet.generated.jooq.default_schema.tables.records.NotificationsRecord;
import io.example.wallet.model.Notification;
import io.example.wallet.model.NotificationBuilder;
import io.example.wallet.model.NotificationKind;
import io.example.wallet.model.NotificationState;
import java.util.List;
import java.util.UUID;

@EkbatanRepository
public class NotificationRepository extends EntityRepository<Notification, NotificationsRecord, Notifications, UUID> {

    public NotificationRepository(DatabaseRegistry databaseRegistry) {
        super(Notification.class, NOTIFICATIONS, NOTIFICATIONS.ID, databaseRegistry);
    }

    public List<Notification> findAllByWalletId(UUID walletId) {
        return readonlyDb()
                .selectFrom(NOTIFICATIONS)
                .where(NOTIFICATIONS.WALLET_ID.eq(walletId))
                .and(NOTIFICATIONS.STATE.ne(NotificationState.DELETED.name()))
                .fetch(this::fromRecord);
    }

    @Override
    public Notification fromRecord(NotificationsRecord r) {
        return NotificationBuilder.notification()
                .id(Id.of(Notification.class, r.getId()))
                .version(r.getVersion())
                .state(NotificationState.valueOf(r.getState()))
                .walletId(r.getWalletId())
                .kind(NotificationKind.valueOf(r.getKind()))
                .recipient(r.getRecipient())
                .message(r.getMessage())
                .build();
    }

    @Override
    public NotificationsRecord toRecord(Notification n) {
        return new NotificationsRecord(
                n.id.getValue(), n.version, n.state.name(), n.walletId, n.kind.name(), n.recipient, n.message);
    }
}
