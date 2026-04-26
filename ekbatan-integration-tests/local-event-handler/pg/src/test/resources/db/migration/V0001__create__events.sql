CREATE SCHEMA IF NOT EXISTS eventlog;

CREATE TABLE eventlog.events (
    id UUID PRIMARY KEY,
    namespace VARCHAR(255) NOT NULL,
    action_id UUID NOT NULL,
    action_name VARCHAR(255) NOT NULL,
    action_params JSONB NOT NULL,
    started_date TIMESTAMP NOT NULL,
    completion_date TIMESTAMP NOT NULL,
    model_id VARCHAR(255),
    model_type VARCHAR(255),
    event_type VARCHAR(255),
    payload JSONB,
    event_date TIMESTAMP NOT NULL,
    delivered BOOLEAN NOT NULL
);

CREATE INDEX idx_events_action_id ON eventlog.events(action_id);

CREATE INDEX events_pending_fanout
    ON eventlog.events (event_date)
    WHERE delivered = FALSE;
