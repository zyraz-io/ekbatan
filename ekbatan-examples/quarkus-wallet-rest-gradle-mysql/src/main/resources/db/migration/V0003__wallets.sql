CREATE TABLE wallets (
    id           CHAR(36) CHARACTER SET ascii NOT NULL,
    version      BIGINT         NOT NULL,
    state        VARCHAR(24)    NOT NULL,
    owner_id     CHAR(36) CHARACTER SET ascii NOT NULL,
    currency     CHAR(3)        NOT NULL,
    balance      DECIMAL(19, 4) NOT NULL,
    created_date DATETIME(6)    NOT NULL,
    updated_date DATETIME(6)    NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_wallets_owner_id ON wallets(owner_id);
