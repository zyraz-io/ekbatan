CREATE TABLE audit_entries (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    note_id VARCHAR(64) NOT NULL,
    widget_id VARCHAR(64) NOT NULL,
    created_date DATETIME(6) NOT NULL
);
