CREATE TABLE wallets (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    name VARCHAR(255) NOT NULL,
    balance DECIMAL(19, 4) NOT NULL,
    created_date TIMESTAMP NOT NULL,
    updated_date TIMESTAMP NOT NULL
);
