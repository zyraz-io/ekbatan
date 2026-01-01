-- Create the dummies table
CREATE TABLE dummies (
    id CHAR(36) CHARACTER SET ascii NOT NULL,
    version BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii NOT NULL,
    currency CHAR(3) NOT NULL,
    balance DECIMAL(19, 4) NOT NULL,
    created_date DATETIME(6) NOT NULL,
    updated_date DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_dummies_owner_id (owner_id)
);