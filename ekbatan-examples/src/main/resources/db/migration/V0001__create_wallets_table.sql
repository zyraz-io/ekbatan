-- Create the wallets table
CREATE TABLE wallets (
    id UUID PRIMARY KEY,
    state VARCHAR(24) NOT NULL,
    owner_id UUID NOT NULL,
    currency CHAR(3) NOT NULL,
    balance DECIMAL(19, 4) NOT NULL,
    created_date TIMESTAMP NOT NULL,
    updated_date TIMESTAMP NOT NULL
);

CREATE INDEX idx_wallets_owner_id ON wallets(owner_id);