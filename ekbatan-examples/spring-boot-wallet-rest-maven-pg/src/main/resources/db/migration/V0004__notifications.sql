CREATE TABLE notifications (
    id        UUID         PRIMARY KEY,
    version   BIGINT       NOT NULL,
    state     VARCHAR(24)  NOT NULL,
    wallet_id UUID         NOT NULL,
    kind      VARCHAR(48)  NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    message   TEXT         NOT NULL
);

CREATE INDEX idx_notifications_wallet_id ON notifications(wallet_id);
