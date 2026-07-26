-- Create the dummies table
CREATE TABLE dummies (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    owner_id UUID NOT NULL,
    currency CHAR(3) NOT NULL,
    balance DECIMAL(19, 4) NOT NULL,
    created_date TIMESTAMP NOT NULL,
    updated_date TIMESTAMP NOT NULL,
    -- Nullable non-text column. Exists so a batch write can carry a column that is NULL in every
    -- row, which is the shape that exposes an untyped NULL bind (Postgres rejects it with 42804).
    reward_points INTEGER,
    -- Converted column. Exists so a batch write carries a value whose Java type only reaches the
    -- database through a jOOQ Converter; binding from the runtime class instead discards it.
    aliases JSONB
);

CREATE INDEX idx_dummies_owner_id ON dummies(owner_id);