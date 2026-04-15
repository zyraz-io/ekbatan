-- Create the dummies table
CREATE TABLE dummies (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    owner_id UUID NOT NULL,
    currency CHAR(3) NOT NULL,
    balance DECIMAL(19, 4) NOT NULL,
    created_date DATETIME(6) NOT NULL,
    updated_date DATETIME(6) NOT NULL
);

CREATE INDEX idx_dummies_owner_id ON dummies(owner_id);