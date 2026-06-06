CREATE TABLE transfer_steps (
    id          UUID        PRIMARY KEY,
    version     BIGINT      NOT NULL,
    state       VARCHAR(24) NOT NULL,
    transfer_id UUID        NOT NULL,
    step        VARCHAR(64) NOT NULL,
    UNIQUE (transfer_id, step)
);

CREATE INDEX idx_transfer_steps_transfer_id ON transfer_steps(transfer_id);
