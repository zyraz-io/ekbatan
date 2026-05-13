CREATE TABLE notifications (
    id        CHAR(36) CHARACTER SET ascii NOT NULL,
    version   BIGINT       NOT NULL,
    state     VARCHAR(24)  NOT NULL,
    wallet_id CHAR(36) CHARACTER SET ascii NOT NULL,
    kind      VARCHAR(48)  NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    message   TEXT         NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_notifications_wallet_id ON notifications(wallet_id);
